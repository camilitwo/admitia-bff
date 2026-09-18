-- Política integral Prekínder v2. Esta migración sólo amplía el esquema aislado
-- de Prekínder; no modifica tablas ni estados de las postulaciones regulares.

ALTER TABLE prekinder_process_configuration
    ADD COLUMN configuration_schema_version INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN total_seats INTEGER NOT NULL DEFAULT 128 CHECK (total_seats > 0),
    ADD COLUMN male_seats INTEGER NOT NULL DEFAULT 64 CHECK (male_seats >= 0),
    ADD COLUMN female_seats INTEGER NOT NULL DEFAULT 64 CHECK (female_seats >= 0),
    ADD COLUMN age_reference_date DATE,
    ADD COLUMN incorporation_fee_amount NUMERIC(12,2),
    ADD COLUMN incorporation_fee_currency VARCHAR(3) NOT NULL DEFAULT 'CLP',
    ADD COLUMN incorporation_fee_glosa VARCHAR(180) NOT NULL DEFAULT 'Incorporación Prekínder',
    ADD COLUMN required_documents JSONB NOT NULL DEFAULT '["BIRTH_CERTIFICATE"]'::jsonb,
    ADD COLUMN schedule_timezone VARCHAR(64) NOT NULL DEFAULT 'America/Santiago',
    ADD COLUMN schedule_day_start TIME NOT NULL DEFAULT '08:00',
    ADD COLUMN schedule_day_end TIME NOT NULL DEFAULT '17:00',
    ADD COLUMN schedule_block_minutes INTEGER NOT NULL DEFAULT 30 CHECK (schedule_block_minutes BETWEEN 10 AND 240),
    ADD COLUMN schedule_max_blocks INTEGER NOT NULL DEFAULT 15 CHECK (schedule_max_blocks > 0),
    ADD COLUMN suggested_parallel_capacity INTEGER NOT NULL DEFAULT 20 CHECK (suggested_parallel_capacity > 0),
    ADD COLUMN academic_group_size INTEGER NOT NULL DEFAULT 3 CHECK (academic_group_size > 0),
    ADD COLUMN psychomotor_group_size INTEGER NOT NULL DEFAULT 9 CHECK (psychomotor_group_size > 0),
    ADD COLUMN academic_required_evaluators INTEGER NOT NULL DEFAULT 3 CHECK (academic_required_evaluators > 0),
    ADD COLUMN psychomotor_required_evaluators INTEGER NOT NULL DEFAULT 6 CHECK (psychomotor_required_evaluators > 0),
    ADD COLUMN initial_offer_hours INTEGER NOT NULL DEFAULT 96 CHECK (initial_offer_hours > 0),
    ADD COLUMN waitlist_offer_hours INTEGER NOT NULL DEFAULT 24 CHECK (waitlist_offer_hours > 0),
    ADD COLUMN advisory_threshold NUMERIC(5,2) NOT NULL DEFAULT 70 CHECK (advisory_threshold BETWEEN 0 AND 100),
    ADD COLUMN result_channel VARCHAR(24) NOT NULL DEFAULT 'EMAIL_ONLY'
        CHECK (result_channel = 'EMAIL_ONLY'),
    ADD COLUMN waitlist_policy JSONB NOT NULL DEFAULT
        '{"partitionBySex":true,"siblingsEligible":false,"segmentPriority":["STAFF_OR_ALUMNI","NEW_FAMILIES"],"tieBreakers":["FORMAL_SUBMITTED_AT","FOLIO"]}'::jsonb,
    ADD COLUMN critical_locked_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_prekinder_seat_distribution CHECK (male_seats + female_seats = total_seats),
    ADD CONSTRAINT ck_prekinder_schedule_window CHECK (schedule_day_end > schedule_day_start),
    ADD CONSTRAINT ck_prekinder_incorporation_fee CHECK (
        incorporation_fee_amount IS NULL OR incorporation_fee_amount > 0
    );

-- El cuestionario se administra por versiones. La versión inicial representa el
-- formulario vigente y queda en borrador hasta que Admisión lo revise y publique.
ALTER TABLE form_template_versions
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN created_by UUID REFERENCES actors(actor_id);

INSERT INTO form_templates(template_id, process_id, code, name)
SELECT gen_random_uuid(), process.process_id, 'COMPLEMENTARY_FORM', 'Cuestionario de postulación Prekínder'
  FROM admission_processes process
 WHERE NOT EXISTS (
     SELECT 1 FROM form_templates template
      WHERE template.process_id = process.process_id AND template.code = 'COMPLEMENTARY_FORM'
 );

INSERT INTO form_template_versions(template_version_id, template_id, version, status, schema_document)
SELECT gen_random_uuid(), template.template_id, 1, 'DRAFT',
       '{"schemaVersion":1,"renderer":"PREKINDER_COMPLEMENTARY_FORM","sections":[{"code":"FAMILY_CONTEXT","label":"Antecedentes familiares"},{"code":"HEALTH_AND_DEVELOPMENT","label":"Salud y desarrollo"},{"code":"SCHOOL_CONTEXT","label":"Contexto escolar"},{"code":"INCLUSION","label":"Inclusión","conditional":true}],"conditionalRequirements":{"inclusion":["CONSENT","SUPPORTING_DOCUMENTS","SPECIFIC_INTERVIEW"]}}'::jsonb
  FROM form_templates template
 WHERE template.code = 'COMPLEMENTARY_FORM'
   AND NOT EXISTS (SELECT 1 FROM form_template_versions version WHERE version.template_id = template.template_id);

ALTER TABLE prekinder_complementary_forms
    ADD COLUMN template_version_id UUID REFERENCES form_template_versions(template_version_id);

-- La entrevista familiar es obligatoria, cualitativa y sensible.
INSERT INTO evaluation_instruments(instrument_code, display_name, capture_mode, sensitive, position)
VALUES ('FAMILY_INTERVIEW', 'Entrevista familiar', 'INDIVIDUAL', true, 8)
ON CONFLICT (instrument_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    capture_mode = EXCLUDED.capture_mode,
    sensitive = EXCLUDED.sensitive,
    active = true;

ALTER TABLE professional_profiles DROP CONSTRAINT IF EXISTS professional_profiles_role_code_check;
ALTER TABLE professional_profiles ADD CONSTRAINT professional_profiles_role_code_check CHECK (role_code IN (
    'ADMIN', 'COORDINATOR', 'EVALUATOR',
    'PK_ADMIN', 'PK_COORDINATOR', 'PK_RECEPTION', 'PK_DATA_ENTRY',
    'PK_REVIEWER', 'PK_COMMITTEE', 'PK_FINAL_APPROVER', 'PK_AUDITOR',
    'PK_EVALUATOR_ACADEMIC', 'PK_EVALUATOR_PSYCHOMOTOR',
    'PK_EVALUATOR_PSYCHOLOGY', 'PK_EVALUATOR_ENTRY_INDICATORS',
    'PK_EVALUATOR_GROUP_OBSERVATION', 'PK_EVALUATOR_FAMILY_INTERVIEW',
    'PK_EVALUATOR_LEARNING_SUPPORT', 'PK_EVALUATOR_DAP'
));

CREATE TABLE process_instrument_policies (
    policy_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    instrument_code VARCHAR(64) NOT NULL REFERENCES evaluation_instruments(instrument_code),
    requirement VARCHAR(24) NOT NULL CHECK (requirement IN ('REQUIRED','APPROVED_REFERRAL')),
    scoring BOOLEAN NOT NULL DEFAULT FALSE,
    weight NUMERIC(5,4) NOT NULL DEFAULT 0 CHECK (weight BETWEEN 0 AND 1),
    maximum_score NUMERIC(10,4),
    sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (process_id, instrument_code),
    CHECK ((scoring AND weight > 0 AND maximum_score > 0)
        OR (NOT scoring AND weight = 0 AND maximum_score IS NULL))
);

INSERT INTO process_instrument_policies(
    policy_id, process_id, instrument_code, requirement, scoring, weight, maximum_score, sensitive
)
SELECT gen_random_uuid(), process.process_id, policy.instrument_code, policy.requirement,
       policy.scoring, policy.weight, policy.maximum_score, policy.sensitive
  FROM admission_processes process
 CROSS JOIN (VALUES
    ('ENTRY_INDICATORS', 'REQUIRED', false, 0.0000, NULL::numeric, false),
    ('ACADEMIC', 'REQUIRED', true, 0.3400, 19::numeric, false),
    ('PSYCHOLOGY', 'REQUIRED', true, 0.3300, 20::numeric, true),
    ('PSYCHOMOTOR', 'REQUIRED', true, 0.3300, 15::numeric, false),
    ('GROUP_OBSERVATION', 'REQUIRED', false, 0.0000, NULL::numeric, false),
    ('FAMILY_INTERVIEW', 'REQUIRED', false, 0.0000, NULL::numeric, true),
    ('LEARNING_SUPPORT', 'APPROVED_REFERRAL', false, 0.0000, NULL::numeric, true),
    ('DAP', 'APPROVED_REFERRAL', false, 0.0000, NULL::numeric, true)
 ) AS policy(instrument_code, requirement, scoring, weight, maximum_score, sensitive)
ON CONFLICT (process_id, instrument_code) DO UPDATE SET
    requirement = EXCLUDED.requirement,
    scoring = EXCLUDED.scoring,
    weight = EXCLUDED.weight,
    maximum_score = EXCLUDED.maximum_score,
    sensitive = EXCLUDED.sensitive,
    version = process_instrument_policies.version + 1,
    updated_at = now();

-- scoring_policies se conserva por compatibilidad, pero deja explícita la fórmula canónica.
UPDATE prekinder_process_configuration
   SET applicant_weight = 1.0000, family_weight = 0.0000,
       payment_amount = COALESCE(payment_amount, 55000),
       configuration_schema_version = 2,
       version = version + 1,
       updated_at = now();

UPDATE scoring_policies
   SET status = 'SUPERSEDED'
 WHERE status = 'PUBLISHED';

INSERT INTO scoring_policies(scoring_policy_id, process_id, version, status,
    applicant_weight, family_weight, formula_document, published_at)
SELECT gen_random_uuid(), process.process_id,
       coalesce((SELECT max(previous.version) + 1 FROM scoring_policies previous
                  WHERE previous.process_id = process.process_id), 1),
       'PUBLISHED', 1.000000, 0.000000,
       '{"formula":"ACADEMIC*0.34+PSYCHOLOGY*0.33+PSYCHOMOTOR*0.33","familyInterview":"QUALITATIVE","automaticDecision":false}'::jsonb,
       now()
  FROM admission_processes process;

ALTER TABLE applications DROP CONSTRAINT IF EXISTS ck_application_status;
ALTER TABLE applications
    ADD COLUMN formal_submitted_at TIMESTAMPTZ,
    ADD COLUMN folio VARCHAR(48),
    ADD COLUMN applicant_sex VARCHAR(12),
    ADD COLUMN configuration_version BIGINT,
    ADD CONSTRAINT ck_application_sex CHECK (applicant_sex IS NULL OR applicant_sex IN ('MALE','FEMALE'));

CREATE UNIQUE INDEX uq_prekinder_application_folio ON applications(folio) WHERE folio IS NOT NULL;
CREATE SEQUENCE prekinder_folio_seq START WITH 1;
CREATE INDEX idx_prekinder_waitlist_order
    ON applications(process_id, applicant_sex, eligibility_category, formal_submitted_at, folio)
    WHERE status = 'WAITLISTED';

CREATE TABLE application_correction_requests (
    correction_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    allowed_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    allowed_document_categories JSONB NOT NULL DEFAULT '[]'::jsonb,
    reason_ciphertext TEXT NOT NULL,
    reason_iv VARCHAR(64) NOT NULL,
    reason_wrapped_dek TEXT NOT NULL,
    reason_wrapped_dek_iv VARCHAR(64) NOT NULL,
    reason_key_version VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','COMPLETED','CANCELLED')),
    requested_by UUID NOT NULL REFERENCES actors(actor_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_open_application_correction
    ON application_correction_requests(application_id) WHERE status = 'OPEN';

-- Conversión conservadora: no inventa formularios, documentos ni evaluaciones.
UPDATE applications application
   SET status = CASE
       WHEN application.status = 'SUBMITTED' AND application.payment_required
            AND application.payment_status <> 'PAID' THEN 'PENDING_PAYMENT'
       WHEN application.status = 'SUBMITTED' THEN 'FORM_PENDING'
       WHEN application.status = 'UNDER_REVIEW' THEN 'UNDER_ADMIN_REVIEW'
       WHEN application.status = 'ACCEPTED' THEN 'PENDING_ENROLLMENT_PAYMENT'
       ELSE application.status
   END,
   configuration_version = config.version,
   updated_at = now()
 FROM prekinder_process_configuration config
 WHERE config.process_id = application.process_id;

ALTER TABLE applications ADD CONSTRAINT ck_application_status CHECK (status IN (
    'DRAFT','PENDING_SEGMENT_VALIDATION','PENDING_PAYMENT','FORM_PENDING',
    'UNDER_ADMIN_REVIEW','REQUIRES_INFORMATION','READY_TO_SCHEDULE','SCHEDULED',
    'IN_EVALUATION','EVALUATION_INCOMPLETE','READY_FOR_COMMITTEE','COMMITTEE_REVIEW',
    'OFFERED','WAITLISTED','NOT_ADMITTED','REQUIRES_REVIEW',
    'PENDING_ENROLLMENT_PAYMENT','ENROLLED','DECLINED','EXPIRED','WITHDRAWN',
    'CANCELLED','INVALIDATED'
));

ALTER TABLE document_metadata
    ADD COLUMN review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (review_status IN ('PENDING','APPROVED','REJECTED','REPLACED')),
    ADD COLUMN review_reason VARCHAR(500),
    ADD COLUMN reviewed_by UUID REFERENCES actors(actor_id),
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    ADD COLUMN replaces_document_id UUID REFERENCES document_metadata(document_id),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- La declaración de inclusión habilita sus propios antecedentes y entrevista,
-- pero nunca crea una derivación a Apoyo o DAP.
ALTER TABLE inclusion_records
    ADD COLUMN declared_at TIMESTAMPTZ,
    ADD COLUMN specific_interview_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN specific_interview_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED'
        CHECK (specific_interview_status IN ('NOT_REQUIRED','PENDING','SCHEDULED','COMPLETED','WAIVED'));

-- Una postulación pagada sólo pasa a revisión si formulario y documentos configurados están completos.
UPDATE applications application
   SET status = 'UNDER_ADMIN_REVIEW',
       formal_submitted_at = COALESCE(application.submitted_at, application.created_at),
       folio = COALESCE(application.folio,
           'PK-' || process.academic_year || '-' || lpad(nextval('prekinder_folio_seq')::text, 7, '0')),
       updated_at = now()
  FROM prekinder_process_configuration config
  JOIN admission_processes process ON process.process_id = config.process_id
 WHERE application.process_id = config.process_id
   AND application.status = 'FORM_PENDING'
   AND (NOT application.payment_required OR application.payment_status = 'PAID')
   AND EXISTS (SELECT 1 FROM prekinder_complementary_forms form
                WHERE form.application_id = application.application_id AND form.submitted)
   AND NOT EXISTS (
       SELECT 1 FROM jsonb_array_elements_text(config.required_documents) required(category)
        WHERE NOT EXISTS (
            SELECT 1 FROM document_metadata document
             WHERE document.application_id = application.application_id
               AND document.category = required.category
               AND document.review_status <> 'REJECTED'
               AND document.scan_status = 'CLEAN'
        )
   );

-- Los expedientes ya avanzados se conservan, pero quedan formalizados con trazabilidad.
UPDATE applications application
   SET formal_submitted_at = COALESCE(application.formal_submitted_at,
           application.submitted_at, application.created_at),
       folio = COALESCE(application.folio,
           'PK-' || process.academic_year || '-' || lpad(nextval('prekinder_folio_seq')::text, 7, '0')),
       updated_at = now()
  FROM admission_processes process
 WHERE process.process_id = application.process_id
   AND application.status IN ('UNDER_ADMIN_REVIEW','READY_TO_SCHEDULE','SCHEDULED','IN_EVALUATION',
       'EVALUATION_INCOMPLETE','READY_FOR_COMMITTEE','COMMITTEE_REVIEW','OFFERED','WAITLISTED',
       'NOT_ADMITTED','REQUIRES_REVIEW','PENDING_ENROLLMENT_PAYMENT','ENROLLED');

CREATE TABLE migration_reconciliation_issues (
    issue_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    issue_code VARCHAR(64) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (application_id, issue_code)
);

INSERT INTO migration_reconciliation_issues(issue_id, application_id, issue_code, detail)
SELECT gen_random_uuid(), application_id, 'APPLICANT_SEX_MISSING',
       'Debe completar el sexo del postulante antes de asignar cupo o lista de espera.'
  FROM applications WHERE applicant_sex IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO migration_reconciliation_issues(issue_id, application_id, issue_code, detail)
SELECT gen_random_uuid(), form.application_id, 'QUESTIONNAIRE_VERSION_MISSING',
       'El formulario histórico no tenía una versión de cuestionario asociada; requiere conciliación, sin inventar una versión.'
  FROM prekinder_complementary_forms form
 WHERE form.template_version_id IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO migration_reconciliation_issues(issue_id, application_id, issue_code, detail)
SELECT gen_random_uuid(), application.application_id, 'FAMILY_INTERVIEW_MISSING',
       'El expediente avanzado no posee una entrevista familiar validada; no se inventó una evaluación.'
  FROM applications application
 WHERE application.status IN ('READY_FOR_COMMITTEE','COMMITTEE_REVIEW','OFFERED','WAITLISTED',
       'NOT_ADMITTED','REQUIRES_REVIEW','PENDING_ENROLLMENT_PAYMENT','ENROLLED')
   AND NOT EXISTS (
       SELECT 1 FROM evaluator_reports report
       JOIN group_instrument_assignments assignment
         ON assignment.assignment_id = report.instrument_assignment_id
        WHERE report.application_id = application.application_id
          AND assignment.instrument_code = 'FAMILY_INTERVIEW'
          AND report.status IN ('VALIDATED','LOCKED')
   )
ON CONFLICT DO NOTHING;

ALTER TABLE evaluator_report_responses
    ADD COLUMN observation_state VARCHAR(24) NOT NULL DEFAULT 'OBSERVED'
        CHECK (observation_state IN ('OBSERVED','NOT_OBSERVED','NOT_APPLICABLE','PENDING'));

UPDATE evaluator_report_responses
   SET observation_state = CASE WHEN not_observed THEN 'NOT_OBSERVED' ELSE 'OBSERVED' END;

DO $$
DECLARE constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
      FROM pg_constraint
     WHERE conrelid = 'evaluator_report_responses'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%not_observed%selected_option_id%'
     LIMIT 1;
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE evaluator_report_responses DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE evaluator_report_responses ADD CONSTRAINT ck_report_response_observation_state CHECK (
    (observation_state = 'OBSERVED' AND NOT not_observed
        AND selected_option_id IS NOT NULL AND observed_value IS NOT NULL)
    OR (observation_state IN ('NOT_OBSERVED','NOT_APPLICABLE','PENDING')
        AND selected_option_id IS NULL AND observed_value IS NULL)
);

CREATE TABLE evaluator_report_reviews (
    review_id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES evaluator_reports(report_id),
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('VALIDATED','RETURNED')),
    reason_ciphertext TEXT,
    reason_iv VARCHAR(64),
    reason_wrapped_dek TEXT,
    reason_wrapped_dek_iv VARCHAR(64),
    reason_key_version VARCHAR(32),
    reviewed_by UUID NOT NULL REFERENCES actors(actor_id),
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_evaluator_report_reviews ON evaluator_report_reviews(report_id, reviewed_at DESC);

ALTER TABLE evaluator_reports
    ADD COLUMN capture_origin VARCHAR(16) NOT NULL DEFAULT 'DIGITAL'
        CHECK (capture_origin IN ('DIGITAL','PAPER')),
    ADD COLUMN observed_at TIMESTAMPTZ,
    ADD COLUMN entered_by UUID REFERENCES actors(actor_id),
    ADD COLUMN second_validated_by UUID REFERENCES actors(actor_id),
    ADD COLUMN second_validated_at TIMESTAMPTZ;

CREATE TABLE family_interview_appointments (
    appointment_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    evaluator_id UUID REFERENCES actors(actor_id),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED'
        CHECK (status IN ('SCHEDULED','COMPLETED','ABSENT','RESCHEDULE_REQUIRED','CANCELLED')),
    rescheduled_from UUID REFERENCES family_interview_appointments(appointment_id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at)
);

CREATE INDEX idx_family_interview_schedule
    ON family_interview_appointments(process_id, starts_at, status);

ALTER TABLE family_interview_appointments
    ADD CONSTRAINT ex_family_interview_evaluator_time
    EXCLUDE USING gist (
        evaluator_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status IN ('SCHEDULED','COMPLETED'));

ALTER TABLE family_interview_appointments
    ADD CONSTRAINT ex_family_interview_application_time
    EXCLUDE USING gist (
        application_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status IN ('SCHEDULED','COMPLETED'));

CREATE TABLE schedule_plan_drafts (
    plan_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    evaluation_date DATE NOT NULL,
    stage VARCHAR(32) NOT NULL CHECK (stage IN ('GROUP_3','GROUP_9')),
    configuration_version BIGINT NOT NULL,
    plan_payload JSONB NOT NULL,
    blockers JSONB NOT NULL DEFAULT '[]'::jsonb,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'PREVIEW'
        CHECK (status IN ('PREVIEW','CONFIRMED','EXPIRED','CANCELLED')),
    created_by UUID NOT NULL REFERENCES actors(actor_id),
    expires_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_schedule_plan_drafts_process
    ON schedule_plan_drafts(process_id, evaluation_date, status, expires_at);

CREATE TABLE seat_ledger (
    seat_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    sex VARCHAR(12) NOT NULL CHECK (sex IN ('MALE','FEMALE')),
    seat_number INTEGER NOT NULL CHECK (seat_number > 0),
    application_id UUID REFERENCES applications(application_id),
    status VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (status IN ('AVAILABLE','RESERVED','ENROLLED')),
    reserved_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (process_id, sex, seat_number),
    UNIQUE (process_id, application_id)
);

INSERT INTO seat_ledger(seat_id, process_id, sex, seat_number)
SELECT gen_random_uuid(), config.process_id, 'MALE', series
  FROM prekinder_process_configuration config
 CROSS JOIN LATERAL generate_series(1, config.male_seats) series
UNION ALL
SELECT gen_random_uuid(), config.process_id, 'FEMALE', series
  FROM prekinder_process_configuration config
 CROSS JOIN LATERAL generate_series(1, config.female_seats) series
ON CONFLICT (process_id, sex, seat_number) DO NOTHING;

CREATE TABLE waitlist_entries (
    entry_id UUID PRIMARY KEY,
    application_id UUID NOT NULL UNIQUE REFERENCES applications(application_id),
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    sex VARCHAR(12) NOT NULL CHECK (sex IN ('MALE','FEMALE')),
    score NUMERIC(10,6) NOT NULL,
    segment_priority INTEGER NOT NULL,
    formal_submitted_at TIMESTAMPTZ NOT NULL,
    folio VARCHAR(48) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','PROMOTED','REMOVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_waitlist_next ON waitlist_entries(
    process_id, sex, score DESC, segment_priority, formal_submitted_at, folio
) WHERE status = 'ACTIVE';

ALTER TABLE offers
    ADD COLUMN offer_source VARCHAR(16) NOT NULL DEFAULT 'INITIAL'
        CHECK (offer_source IN ('INITIAL','WAITLIST')),
    ADD COLUMN seat_id UUID REFERENCES seat_ledger(seat_id);

ALTER TABLE offers DROP CONSTRAINT IF EXISTS ck_offer_status;
ALTER TABLE offers ADD CONSTRAINT ck_offer_status
    CHECK (status IN ('OFFERED','ACCEPTED','DECLINED','EXPIRED','CANCELLED','FULFILLED'));
ALTER TABLE offer_status_history DROP CONSTRAINT IF EXISTS offer_status_history_to_status_check;
ALTER TABLE offer_status_history ADD CONSTRAINT offer_status_history_to_status_check
    CHECK (to_status IN ('OFFERED','ACCEPTED','DECLINED','EXPIRED','CANCELLED','FULFILLED'));

ALTER TABLE prekinder_payments
    ADD COLUMN payment_kind VARCHAR(24) NOT NULL DEFAULT 'APPLICATION'
        CHECK (payment_kind IN ('APPLICATION','INCORPORATION'));

CREATE INDEX idx_prekinder_payment_kind
    ON prekinder_payments(application_id, payment_kind, created_at DESC);

-- Los grupos dejan de estar amarrados en base de datos a 3/9; esos valores pasan a configuración.
ALTER TABLE evaluation_groups DROP CONSTRAINT IF EXISTS evaluation_groups_stage_check;
ALTER TABLE evaluation_groups
    ADD CONSTRAINT evaluation_groups_stage_check
        CHECK (stage IN ('GROUP_3','GROUP_9','FAMILY_INTERVIEW'));

DO $$
DECLARE constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
      FROM pg_constraint
     WHERE conrelid = 'evaluation_groups'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%stage%capacity%required_evaluators%'
     LIMIT 1;
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE evaluation_groups DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE evaluation_groups
    ADD CONSTRAINT ck_evaluation_group_positive_capacity
        CHECK (capacity > 0 AND required_evaluators > 0);

-- Plantillas de resultado por correo: no remiten a una publicación en el portal.
UPDATE prekinder_communication_template_versions version
   SET status = 'SUPERSEDED'
  FROM prekinder_communication_templates template
 WHERE template.communication_template_id = version.communication_template_id
   AND template.event_code LIKE 'RESULT_%'
   AND version.status = 'PUBLISHED';

INSERT INTO prekinder_communication_template_versions(
    communication_template_version_id, communication_template_id, version, status,
    subject, body_html, allowed_variables, published_at
)
SELECT gen_random_uuid(), template.communication_template_id, previous.version + 1, 'PUBLISHED',
       previous.subject,
       replace(replace(previous.body_html,
           ' ya está disponible en el portal', ' se informa en este correo'),
           ' ya está en el portal', ' se informa en este correo'),
       (previous.allowed_variables - 'portalUrl'), now()
  FROM prekinder_communication_templates template
  JOIN LATERAL (
      SELECT version.* FROM prekinder_communication_template_versions version
       WHERE version.communication_template_id = template.communication_template_id
       ORDER BY version.version DESC LIMIT 1
  ) previous ON true
 WHERE template.event_code LIKE 'RESULT_%';

INSERT INTO prekinder_communication_templates(communication_template_id, process_id, event_code, name)
SELECT gen_random_uuid(), process.process_id, 'WAITLIST_PROMOTED', 'Promoción desde lista de espera'
  FROM admission_processes process
ON CONFLICT (process_id, event_code) DO NOTHING;

INSERT INTO prekinder_communication_template_versions(
    communication_template_version_id, communication_template_id, version, status,
    subject, body_html, allowed_variables, published_at
)
SELECT gen_random_uuid(), template.communication_template_id, 1, 'PUBLISHED',
       'Cupo disponible para Prekínder',
       '<h1>Cupo disponible</h1><p>Se ha liberado un cupo para {{applicantName}}. Ingresa al portal para responder la oferta dentro del plazo informado.</p>',
       '["applicantName","processName","portalUrl"]'::jsonb, now()
  FROM prekinder_communication_templates template
 WHERE template.event_code = 'WAITLIST_PROMOTED'
   AND NOT EXISTS (SELECT 1 FROM prekinder_communication_template_versions version
                    WHERE version.communication_template_id = template.communication_template_id);
