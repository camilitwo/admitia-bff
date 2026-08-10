-- Contratos operativos requeridos por las consolas Prekínder del frontend.
-- Migración aditiva sobre la base aislada; no toca el esquema legacy.

CREATE TABLE evaluation_instruments (
    instrument_code VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    capture_mode VARCHAR(24) NOT NULL
        CHECK (capture_mode IN ('GROUP_PARALLEL','INDIVIDUAL','DERIVED_INDIVIDUAL')),
    sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    position INTEGER NOT NULL UNIQUE CHECK (position > 0)
);

INSERT INTO evaluation_instruments(instrument_code, display_name, capture_mode, sensitive, position) VALUES
    ('ACADEMIC', 'Académico', 'GROUP_PARALLEL', false, 1),
    ('PSYCHOMOTOR', 'Psicomotricidad', 'GROUP_PARALLEL', false, 2),
    ('PSYCHOLOGY', 'Psicología', 'INDIVIDUAL', true, 3),
    ('ENTRY_INDICATORS', 'Indicadores de ingreso', 'GROUP_PARALLEL', false, 4),
    ('GROUP_OBSERVATION', 'Observación grupal', 'GROUP_PARALLEL', false, 5),
    ('LEARNING_SUPPORT', 'Apoyo al Aprendizaje', 'DERIVED_INDIVIDUAL', true, 6),
    ('DAP', 'DAP', 'DERIVED_INDIVIDUAL', true, 7);

CREATE TABLE prekinder_actor_role_assignments (
    assignment_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    actor_id UUID NOT NULL REFERENCES actors(actor_id),
    role_code VARCHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ,
    assigned_by UUID NOT NULL REFERENCES actors(actor_id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE UNIQUE INDEX uq_pk_active_actor_role
    ON prekinder_actor_role_assignments(process_id, actor_id, role_code) WHERE active;

CREATE TABLE professional_instrument_authorizations (
    authorization_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    professional_id UUID NOT NULL REFERENCES professional_profiles(professional_id),
    instrument_code VARCHAR(64) NOT NULL REFERENCES evaluation_instruments(instrument_code),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ,
    authorized_by UUID NOT NULL REFERENCES actors(actor_id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE UNIQUE INDEX uq_active_professional_instrument
    ON professional_instrument_authorizations(process_id, professional_id, instrument_code) WHERE active;

CREATE TABLE group_instrument_assignments (
    assignment_id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES evaluation_groups(group_id),
    instrument_code VARCHAR(64) NOT NULL REFERENCES evaluation_instruments(instrument_code),
    evaluator_id UUID NOT NULL REFERENCES actors(actor_id),
    template_version_id UUID NOT NULL REFERENCES evaluation_template_versions(evaluation_template_version_id),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','CONFIRMED','IN_PROGRESS','SUBMITTED','COMPLETED','REPLACED','CANCELLED')),
    assigned_by UUID NOT NULL REFERENCES actors(actor_id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_active_group_instrument
    ON group_instrument_assignments(group_id, instrument_code)
    WHERE status IN ('ACTIVE','CONFIRMED','IN_PROGRESS','SUBMITTED');
CREATE INDEX idx_instrument_assignment_evaluator
    ON group_instrument_assignments(evaluator_id, status, assigned_at);

ALTER TABLE evaluator_reports
    ADD COLUMN instrument_assignment_id UUID REFERENCES group_instrument_assignments(assignment_id),
    ADD COLUMN instrument_code VARCHAR(64) REFERENCES evaluation_instruments(instrument_code),
    ADD COLUMN submitted_at TIMESTAMPTZ,
    ADD COLUMN validated_at TIMESTAMPTZ,
    ADD COLUMN validated_by UUID REFERENCES actors(actor_id),
    ADD COLUMN returned_at TIMESTAMPTZ;

ALTER TABLE evaluator_reports DROP CONSTRAINT IF EXISTS evaluator_reports_status_check;
ALTER TABLE evaluator_reports ADD CONSTRAINT evaluator_reports_status_check CHECK (status IN (
    'PENDING','IN_PROGRESS','COMPLETED','SUBMITTED','RETURNED','VALIDATED','LOCKED',
    'REOPENED','SUPERSEDED','CANCELLED'
));

CREATE INDEX idx_reports_instrument ON evaluator_reports(instrument_code, status, updated_at DESC);

CREATE TABLE evaluator_report_response_notes (
    response_note_id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES evaluator_reports(report_id),
    criterion_id UUID NOT NULL REFERENCES evaluation_criteria(criterion_id),
    ciphertext TEXT NOT NULL,
    iv VARCHAR(64) NOT NULL,
    wrapped_dek TEXT NOT NULL,
    wrapped_dek_iv VARCHAR(64) NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    operation_id UUID NOT NULL UNIQUE,
    updated_by UUID NOT NULL REFERENCES actors(actor_id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (report_id, criterion_id)
);

ALTER TABLE evaluation_group_members
    ADD COLUMN attendance_detail VARCHAR(32),
    ADD COLUMN attendance_reason_code VARCHAR(64),
    ADD COLUMN attendance_recorded_by UUID REFERENCES actors(actor_id),
    ADD COLUMN attendance_recorded_at TIMESTAMPTZ;

CREATE TABLE prekinder_operational_incidents (
    incident_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    group_id UUID REFERENCES evaluation_groups(group_id),
    application_id UUID REFERENCES applications(application_id),
    incident_type VARCHAR(48) NOT NULL,
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('INFO','WARNING','CRITICAL')),
    status VARCHAR(24) NOT NULL CHECK (status IN ('OPEN','RESOLVED','CANCELLED')),
    description_ciphertext TEXT NOT NULL,
    description_iv VARCHAR(64) NOT NULL,
    description_wrapped_dek TEXT NOT NULL,
    description_wrapped_dek_iv VARCHAR(64) NOT NULL,
    description_key_version VARCHAR(32) NOT NULL,
    reported_by UUID NOT NULL REFERENCES actors(actor_id),
    resolved_by UUID REFERENCES actors(actor_id),
    reported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_pk_incidents_process_status
    ON prekinder_operational_incidents(process_id, status, reported_at DESC);

