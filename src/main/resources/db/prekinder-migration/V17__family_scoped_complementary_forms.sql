ALTER TABLE prekinder_complementary_forms
    ADD COLUMN family_id UUID REFERENCES families(family_id),
    ADD COLUMN process_id UUID REFERENCES admission_processes(process_id);

UPDATE prekinder_complementary_forms form
   SET family_id = applicant.family_id,
       process_id = application.process_id
  FROM applications application
  JOIN applicants applicant ON applicant.applicant_id = application.applicant_id
 WHERE application.application_id = form.application_id;

CREATE TABLE prekinder_complementary_form_archive (
    archive_id UUID PRIMARY KEY,
    original_form_id UUID NOT NULL,
    family_id UUID NOT NULL,
    process_id UUID NOT NULL,
    application_id UUID NOT NULL,
    ciphertext TEXT NOT NULL,
    iv VARCHAR(64) NOT NULL,
    wrapped_dek TEXT NOT NULL,
    wrapped_dek_iv VARCHAR(64) NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    submitted BOOLEAN NOT NULL,
    submitted_at TIMESTAMPTZ,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason VARCHAR(80) NOT NULL
);

INSERT INTO prekinder_complementary_form_archive
SELECT gen_random_uuid(), form_id, family_id, process_id, application_id, ciphertext, iv,
       wrapped_dek, wrapped_dek_iv, key_version, submitted, submitted_at, version,
       created_at, updated_at, now(), 'DUPLICATE_FAMILY_PROCESS'
  FROM (
      SELECT form.*, row_number() OVER (
          PARTITION BY family_id, process_id ORDER BY updated_at DESC, form_id DESC
      ) AS position
        FROM prekinder_complementary_forms form
  ) ranked
 WHERE position > 1;

DELETE FROM prekinder_complementary_forms form
 WHERE EXISTS (SELECT 1 FROM prekinder_complementary_form_archive archive
                WHERE archive.original_form_id = form.form_id);

ALTER TABLE prekinder_complementary_forms ALTER COLUMN family_id SET NOT NULL;
ALTER TABLE prekinder_complementary_forms ALTER COLUMN process_id SET NOT NULL;
ALTER TABLE prekinder_complementary_forms DROP CONSTRAINT IF EXISTS prekinder_complementary_forms_application_id_key;
ALTER TABLE prekinder_complementary_forms
    ADD CONSTRAINT uq_prekinder_complementary_form_family_process UNIQUE(family_id, process_id);
