-- Configuración operativa versionada por proceso. Las columnas explícitas
-- mantienen las reglas críticas consultables y auditablemente estables.
CREATE TABLE prekinder_process_configuration (
    process_id UUID PRIMARY KEY REFERENCES admission_processes(process_id),
    payment_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    payment_amount NUMERIC(12,2),
    payment_currency VARCHAR(3) NOT NULL DEFAULT 'CLP',
    payment_glosa VARCHAR(180) NOT NULL DEFAULT 'Postulación Prekínder',
    payment_due_days INTEGER NOT NULL DEFAULT 3 CHECK (payment_due_days BETWEEN 1 AND 30),
    inclusion_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    inclusion_documents_required BOOLEAN NOT NULL DEFAULT FALSE,
    minimum_age_months INTEGER NOT NULL DEFAULT 48 CHECK (minimum_age_months BETWEEN 36 AND 84),
    maximum_age_months INTEGER NOT NULL DEFAULT 71 CHECK (maximum_age_months BETWEEN 36 AND 96),
    applicant_weight NUMERIC(5,4) NOT NULL DEFAULT 0.7000,
    family_weight NUMERIC(5,4) NOT NULL DEFAULT 0.3000,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (payment_amount IS NULL OR payment_amount > 0),
    CHECK (NOT payment_enabled OR payment_amount IS NOT NULL),
    CHECK (minimum_age_months <= maximum_age_months),
    CHECK (applicant_weight >= 0 AND family_weight >= 0 AND applicant_weight + family_weight = 1)
);

INSERT INTO prekinder_process_configuration(process_id)
SELECT process_id FROM admission_processes
ON CONFLICT (process_id) DO NOTHING;

CREATE TABLE prekinder_process_configuration_versions (
    configuration_version_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    version BIGINT NOT NULL,
    snapshot JSONB NOT NULL,
    created_by UUID REFERENCES actors(actor_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (process_id, version)
);

INSERT INTO prekinder_process_configuration_versions(configuration_version_id, process_id, version, snapshot)
SELECT gen_random_uuid(), config.process_id, config.version,
       jsonb_build_object(
           'paymentEnabled', config.payment_enabled, 'paymentAmount', config.payment_amount,
           'paymentCurrency', config.payment_currency, 'paymentGlosa', config.payment_glosa,
           'paymentDueDays', config.payment_due_days, 'inclusionEnabled', config.inclusion_enabled,
           'inclusionDocumentsRequired', config.inclusion_documents_required,
           'minimumAgeMonths', config.minimum_age_months, 'maximumAgeMonths', config.maximum_age_months,
           'applicantWeight', config.applicant_weight, 'familyWeight', config.family_weight)
  FROM prekinder_process_configuration config
ON CONFLICT (process_id, version) DO NOTHING;

INSERT INTO scoring_policies(scoring_policy_id, process_id, version, status,
    applicant_weight, family_weight, formula_document, published_at)
SELECT gen_random_uuid(), config.process_id,
       coalesce((SELECT max(existing.version) + 1 FROM scoring_policies existing
                  WHERE existing.process_id = config.process_id), 1),
       'PUBLISHED', config.applicant_weight, config.family_weight,
       jsonb_build_object('source', 'PROCESS_CONFIGURATION', 'configurationVersion', config.version), now()
  FROM prekinder_process_configuration config
 WHERE NOT EXISTS (SELECT 1 FROM scoring_policies existing
                    WHERE existing.process_id = config.process_id AND existing.status = 'PUBLISHED');

-- evaluation_templates pasa a funcionar como catálogo. Las pautas existentes
-- conservan su process_id; las nuevas pueden ser globales y reutilizables.
ALTER TABLE evaluation_templates
    ALTER COLUMN process_id DROP NOT NULL,
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD CONSTRAINT ck_evaluation_template_status CHECK (status IN ('ACTIVE','ARCHIVED'));

CREATE UNIQUE INDEX uq_global_active_rubric_code
    ON evaluation_templates(type_code)
    WHERE process_id IS NULL AND status = 'ACTIVE';

CREATE TABLE process_rubric_assignments (
    assignment_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    instrument_code VARCHAR(64) NOT NULL,
    evaluation_template_version_id UUID NOT NULL
        REFERENCES evaluation_template_versions(evaluation_template_version_id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    assigned_by UUID REFERENCES actors(actor_id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_active_process_rubric_assignment
    ON process_rubric_assignments(process_id, instrument_code)
    WHERE active;

INSERT INTO process_rubric_assignments(
    assignment_id, process_id, instrument_code, evaluation_template_version_id
)
SELECT gen_random_uuid(), template.process_id, template.type_code,
       version.evaluation_template_version_id
  FROM evaluation_templates template
  JOIN LATERAL (
      SELECT candidate.evaluation_template_version_id
        FROM evaluation_template_versions candidate
       WHERE candidate.evaluation_template_id = template.evaluation_template_id
         AND candidate.status = 'PUBLISHED'
       ORDER BY candidate.version DESC
       LIMIT 1
  ) version ON TRUE
 WHERE template.process_id IS NOT NULL
ON CONFLICT DO NOTHING;

CREATE TABLE prekinder_communication_templates (
    communication_template_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    event_code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (process_id, event_code)
);

CREATE TABLE prekinder_communication_template_versions (
    communication_template_version_id UUID PRIMARY KEY,
    communication_template_id UUID NOT NULL
        REFERENCES prekinder_communication_templates(communication_template_id),
    version INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','SUPERSEDED')),
    subject VARCHAR(200) NOT NULL,
    body_html TEXT NOT NULL,
    allowed_variables JSONB NOT NULL DEFAULT '[]'::jsonb,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (communication_template_id, version)
);

CREATE UNIQUE INDEX uq_published_process_communication
    ON prekinder_communication_template_versions(communication_template_id)
    WHERE status = 'PUBLISHED';

CREATE OR REPLACE FUNCTION prevent_published_communication_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        IF TG_OP = 'UPDATE'
           AND NEW.status = 'SUPERSEDED'
           AND NEW.communication_template_id = OLD.communication_template_id
           AND NEW.version = OLD.version
           AND NEW.subject = OLD.subject
           AND NEW.body_html = OLD.body_html
           AND NEW.allowed_variables = OLD.allowed_variables
           AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at
           AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'PUBLISHED_COMMUNICATION_IMMUTABLE';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_published_communication_immutable
    BEFORE UPDATE OR DELETE ON prekinder_communication_template_versions
    FOR EACH ROW EXECUTE FUNCTION prevent_published_communication_mutation();

INSERT INTO prekinder_communication_templates(
    communication_template_id, process_id, event_code, name
)
SELECT gen_random_uuid(), process.process_id, defaults.event_code, defaults.template_name
  FROM admission_processes process
 CROSS JOIN (VALUES
    ('APPLICATION_SUBMITTED', 'Postulación recibida'),
    ('SCHEDULE_ASSIGNED', 'Jornada agendada'),
    ('RESULT_ACCEPTED', 'Resultado aceptado'),
    ('RESULT_WAITLIST', 'Resultado lista de espera'),
    ('RESULT_REJECTED', 'Resultado no admitido'),
    ('RESULT_RECTIFICATION', 'Rectificación de resultado')
 ) AS defaults(event_code, template_name)
ON CONFLICT (process_id, event_code) DO NOTHING;

INSERT INTO prekinder_communication_template_versions(
    communication_template_version_id, communication_template_id, version,
    status, subject, body_html, allowed_variables, published_at
)
SELECT gen_random_uuid(), template.communication_template_id, 1, 'PUBLISHED',
       CASE
         WHEN template.event_code = 'APPLICATION_SUBMITTED' THEN 'Postulación Prekínder recibida'
         WHEN template.event_code = 'SCHEDULE_ASSIGNED' THEN 'Jornada de evaluación Prekínder'
         WHEN template.event_code = 'RESULT_RECTIFICATION' THEN 'Rectificación de resultado de admisión Prekínder'
         ELSE 'Resultado proceso de admisión Prekínder'
       END,
       CASE
         WHEN template.event_code = 'APPLICATION_SUBMITTED'
           THEN '<h1>Postulación recibida</h1><p>Recibimos la postulación de {{applicantName}}.</p>'
         WHEN template.event_code = 'SCHEDULE_ASSIGNED'
           THEN '<h1>Jornada agendada</h1><p>Revisa en el portal la fecha y ubicación asignadas a {{applicantName}}.</p>'
         ELSE '<h1>Resultado de admisión Prekínder</h1><p>El resultado de {{applicantName}} ya está disponible en el portal.</p>'
       END,
       '["applicantName","processName","portalUrl"]'::jsonb,
       now()
  FROM prekinder_communication_templates template
 WHERE NOT EXISTS (
     SELECT 1 FROM prekinder_communication_template_versions existing
      WHERE existing.communication_template_id = template.communication_template_id
 );

CREATE TABLE publication_previews (
    preview_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    fingerprint VARCHAR(64) NOT NULL,
    eligible_count INTEGER NOT NULL,
    blocked_count INTEGER NOT NULL,
    created_by UUID NOT NULL REFERENCES actors(actor_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at)
);

CREATE TABLE guardian_application_drafts (
    draft_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    actor_id UUID NOT NULL REFERENCES actors(actor_id),
    current_section INTEGER NOT NULL DEFAULT 0 CHECK (current_section BETWEEN 0 AND 20),
    ciphertext TEXT NOT NULL,
    iv TEXT NOT NULL,
    wrapped_dek TEXT NOT NULL,
    wrapped_dek_iv TEXT NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (process_id, actor_id)
);

ALTER TABLE publication_batches
    ADD COLUMN preview_id UUID REFERENCES publication_previews(preview_id),
    ADD COLUMN idempotency_key UUID,
    ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    ADD COLUMN last_error_code VARCHAR(64),
    ADD CONSTRAINT ck_publication_batch_mode CHECK (mode IN ('IMMEDIATE','SCHEDULED'));

ALTER TABLE publication_batch_items
    ADD COLUMN communication_template_version_id UUID
        REFERENCES prekinder_communication_template_versions(communication_template_version_id);

CREATE UNIQUE INDEX uq_publication_batch_idempotency
    ON publication_batches(process_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX uq_active_offer_per_application
    ON offers(application_id) WHERE status = 'OFFERED';

CREATE INDEX idx_publication_preview_expiry ON publication_previews(expires_at);
CREATE INDEX idx_process_rubric_lookup
    ON process_rubric_assignments(process_id, instrument_code) WHERE active;

-- Una versión publicada conserva contenido inmutable, pero puede pasar a
-- SUPERSEDED cuando se publica su sucesora.
CREATE OR REPLACE FUNCTION prevent_published_rubric_version_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        IF TG_OP = 'UPDATE'
           AND NEW.status = 'SUPERSEDED'
           AND NEW.evaluation_template_id = OLD.evaluation_template_id
           AND NEW.version = OLD.version
           AND NEW.maximum_score IS NOT DISTINCT FROM OLD.maximum_score
           AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at
           AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'PUBLISHED_RUBRIC_IMMUTABLE';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE UNIQUE INDEX uq_published_rubric_version
    ON evaluation_template_versions(evaluation_template_id)
    WHERE status = 'PUBLISHED';
