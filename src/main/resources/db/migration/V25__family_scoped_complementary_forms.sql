CREATE TABLE families (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE family_members (
    family_id BIGINT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (family_id, user_id)
);

ALTER TABLE applications ADD COLUMN family_id BIGINT REFERENCES families(id);

-- Existing accounts are the safest deterministic seed. RUT matches are deliberately
-- left as candidates for explicit confirmation instead of silently merging households.
INSERT INTO families(id)
SELECT nextval(pg_get_serial_sequence('families', 'id'))
  FROM (SELECT DISTINCT applicant_user_id FROM applications WHERE applicant_user_id IS NOT NULL) users;

WITH numbered_users AS (
    SELECT applicant_user_id, row_number() OVER (ORDER BY applicant_user_id) AS position
      FROM (SELECT DISTINCT applicant_user_id FROM applications WHERE applicant_user_id IS NOT NULL) source
), numbered_families AS (
    SELECT id, row_number() OVER (ORDER BY id) AS position FROM families
)
UPDATE applications application
   SET family_id = family.id
  FROM numbered_users account JOIN numbered_families family USING (position)
 WHERE application.applicant_user_id = account.applicant_user_id;

INSERT INTO families(id)
SELECT nextval(pg_get_serial_sequence('families', 'id'))
  FROM applications WHERE family_id IS NULL;

WITH orphan_applications AS (
    SELECT id, row_number() OVER (ORDER BY id) AS position FROM applications WHERE family_id IS NULL
), unused_families AS (
    SELECT family.id, row_number() OVER (ORDER BY family.id) AS position
      FROM families family
     WHERE NOT EXISTS (SELECT 1 FROM applications application WHERE application.family_id = family.id)
)
UPDATE applications application
   SET family_id = family.id
  FROM orphan_applications orphan JOIN unused_families family USING (position)
 WHERE application.id = orphan.id;

ALTER TABLE applications ALTER COLUMN family_id SET NOT NULL;
CREATE INDEX idx_applications_family_process ON applications(family_id, academic_year);

INSERT INTO family_members(family_id, user_id)
SELECT DISTINCT family_id, applicant_user_id FROM applications WHERE applicant_user_id IS NOT NULL
ON CONFLICT DO NOTHING;

CREATE TABLE complementary_form_archive (
    archived_id BIGSERIAL PRIMARY KEY,
    original_form_id BIGINT NOT NULL,
    family_id BIGINT NOT NULL,
    process_key VARCHAR(80) NOT NULL,
    application_id BIGINT,
    form_data JSONB NOT NULL,
    is_submitted BOOLEAN NOT NULL,
    submitted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    archived_at TIMESTAMP NOT NULL DEFAULT NOW(),
    reason VARCHAR(80) NOT NULL
);

ALTER TABLE complementary_forms
    ADD COLUMN family_id BIGINT REFERENCES families(id),
    ADD COLUMN process_key VARCHAR(80),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE complementary_forms form
   SET family_id = application.family_id,
       process_key = 'GENERAL:' || COALESCE(application.academic_year, EXTRACT(YEAR FROM application.submission_date)::INTEGER)::TEXT
  FROM applications application
 WHERE application.id = form.application_id;

INSERT INTO complementary_form_archive(
    original_form_id, family_id, process_key, application_id, form_data, is_submitted,
    submitted_at, created_at, updated_at, reason
)
SELECT id, family_id, process_key, application_id, form_data, is_submitted,
       submitted_at, created_at, updated_at, 'DUPLICATE_FAMILY_PROCESS'
  FROM (
      SELECT form.*, row_number() OVER (
          PARTITION BY family_id, process_key ORDER BY updated_at DESC, id DESC
      ) AS position
        FROM complementary_forms form
  ) ranked
 WHERE position > 1;

DELETE FROM complementary_forms form
 WHERE EXISTS (
     SELECT 1 FROM complementary_form_archive archive
      WHERE archive.original_form_id = form.id
        AND archive.reason = 'DUPLICATE_FAMILY_PROCESS'
 );

ALTER TABLE complementary_forms ALTER COLUMN family_id SET NOT NULL;
ALTER TABLE complementary_forms ALTER COLUMN process_key SET NOT NULL;
ALTER TABLE complementary_forms ALTER COLUMN application_id DROP NOT NULL;
ALTER TABLE complementary_forms DROP CONSTRAINT IF EXISTS complementary_forms_application_id_key;
ALTER TABLE complementary_forms ADD CONSTRAINT uq_complementary_form_family_process UNIQUE (family_id, process_key);

CREATE TABLE complementary_form_revisions (
    id BIGSERIAL PRIMARY KEY,
    form_id BIGINT NOT NULL REFERENCES complementary_forms(id) ON DELETE CASCADE,
    version BIGINT NOT NULL,
    form_data JSONB NOT NULL,
    submitted BOOLEAN NOT NULL,
    changed_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(form_id, version)
);
