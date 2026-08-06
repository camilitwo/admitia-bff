-- Flujo operativo Prekinder: oleadas, grupos, reportes independientes y publicacion.
-- Migracion estrictamente aditiva; no reutiliza ni modifica la base legacy.

CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE actors
    ALTER COLUMN legacy_user_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS external_subject VARCHAR(160),
    ADD COLUMN IF NOT EXISTS email_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS profile_version BIGINT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS uq_actors_external_subject
    ON actors(external_subject) WHERE external_subject IS NOT NULL;

CREATE TABLE process_waves (
    wave_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    wave_type VARCHAR(32) NOT NULL CHECK (wave_type IN ('SIBLINGS','STAFF_OR_ALUMNI','NEW_FAMILIES')),
    position INTEGER NOT NULL CHECK (position BETWEEN 1 AND 3),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','CLOSED','CANCELLED')),
    opens_at TIMESTAMPTZ,
    closes_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (process_id, wave_type),
    UNIQUE (process_id, position),
    CHECK (closes_at IS NULL OR opens_at IS NULL OR closes_at > opens_at)
);

ALTER TABLE applications
    DROP CONSTRAINT ck_application_status,
    ADD COLUMN IF NOT EXISTS wave_id UUID REFERENCES process_waves(wave_id),
    ADD COLUMN IF NOT EXISTS eligibility_category VARCHAR(32),
    ADD COLUMN IF NOT EXISTS eligibility_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS invalidated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS invalidated_by UUID REFERENCES actors(actor_id),
    ADD COLUMN IF NOT EXISTS applicant_identity_hash VARCHAR(64),
    ADD CONSTRAINT ck_application_status CHECK (status IN (
        'DRAFT','SUBMITTED','UNDER_REVIEW','REQUIRES_INFORMATION','SCHEDULED','IN_EVALUATION',
        'READY_FOR_COMMITTEE','COMMITTEE_REVIEW','OFFERED','WAITLISTED','NOT_ADMITTED',
        'REQUIRES_REVIEW','ACCEPTED','DECLINED','CANCELLED','INVALIDATED'
    )),
    ADD CONSTRAINT ck_application_eligibility_category CHECK (
        eligibility_category IS NULL OR eligibility_category IN ('SIBLINGS','STAFF_OR_ALUMNI','NEW_FAMILIES')
    ),
    ADD CONSTRAINT ck_application_eligibility_status CHECK (
        eligibility_status IN ('PENDING','VERIFIED','REJECTED','NOT_REQUIRED')
    );

CREATE UNIQUE INDEX uq_active_application_identity_process
    ON applications(process_id, applicant_identity_hash)
    WHERE applicant_identity_hash IS NOT NULL AND status <> 'INVALIDATED';

CREATE TABLE eligibility_declarations (
    declaration_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    wave_id UUID NOT NULL REFERENCES process_waves(wave_id),
    category VARCHAR(32) NOT NULL CHECK (category IN ('SIBLINGS','STAFF_OR_ALUMNI','NEW_FAMILIES')),
    ciphertext TEXT NOT NULL,
    iv VARCHAR(64) NOT NULL,
    wrapped_dek TEXT NOT NULL,
    wrapped_dek_iv VARCHAR(64) NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','VERIFIED','REJECTED')),
    review_reason_ciphertext TEXT,
    review_reason_iv VARCHAR(64),
    review_reason_wrapped_dek TEXT,
    review_reason_wrapped_dek_iv VARCHAR(64),
    review_reason_key_version VARCHAR(32),
    reviewed_by UUID REFERENCES actors(actor_id),
    reviewed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (application_id)
);

CREATE TABLE professional_profiles (
    professional_id UUID PRIMARY KEY REFERENCES actors(actor_id),
    display_name VARCHAR(160) NOT NULL,
    email VARCHAR(254) NOT NULL,
    specialty VARCHAR(96),
    role_code VARCHAR(48) NOT NULL CHECK (role_code IN ('ADMIN','COORDINATOR','EVALUATOR')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_professional_profiles_email ON professional_profiles(lower(email));
CREATE INDEX idx_actors_email_hash ON actors(email_hash) WHERE email_hash IS NOT NULL;

CREATE TABLE professional_availability (
    availability_id UUID PRIMARY KEY,
    professional_id UUID NOT NULL REFERENCES professional_profiles(professional_id),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','UNAVAILABLE')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    UNIQUE (professional_id, starts_at, ends_at)
);

CREATE TABLE prekinder_rooms (
    room_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    capacity INTEGER NOT NULL CHECK (capacity >= 3),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (process_id, code)
);

CREATE TABLE evaluation_days (
    day_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    day_date DATE NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','CLOSED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (process_id, day_date)
);

CREATE TABLE evaluation_blocks (
    block_id UUID PRIMARY KEY,
    day_id UUID NOT NULL REFERENCES evaluation_days(day_id),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes BETWEEN 10 AND 240),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    UNIQUE (day_id, starts_at, ends_at)
);

CREATE TABLE evaluation_groups (
    group_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    day_id UUID NOT NULL REFERENCES evaluation_days(day_id),
    block_id UUID NOT NULL REFERENCES evaluation_blocks(block_id),
    room_id UUID NOT NULL REFERENCES prekinder_rooms(room_id),
    stage VARCHAR(24) NOT NULL CHECK (stage IN ('GROUP_3','GROUP_9')),
    code VARCHAR(64) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    capacity INTEGER NOT NULL,
    required_evaluators INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','CONFIRMED','IN_PROGRESS','COMPLETED','CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (process_id, code),
    CHECK (ends_at > starts_at),
    CHECK ((stage = 'GROUP_3' AND capacity = 3 AND required_evaluators = 3)
        OR (stage = 'GROUP_9' AND capacity = 9 AND required_evaluators = 6))
);

ALTER TABLE evaluation_groups ADD CONSTRAINT ex_group_room_time
    EXCLUDE USING gist (
        room_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status IN ('CONFIRMED','IN_PROGRESS'));

CREATE TABLE evaluation_group_members (
    member_id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES evaluation_groups(group_id),
    application_id UUID NOT NULL REFERENCES applications(application_id),
    status VARCHAR(24) NOT NULL DEFAULT 'ASSIGNED' CHECK (status IN ('ASSIGNED','ATTENDED','ABSENT','MOVED','CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (group_id, application_id)
);

CREATE TABLE applicant_group_bookings (
    booking_id UUID PRIMARY KEY,
    member_id UUID NOT NULL UNIQUE REFERENCES evaluation_group_members(member_id),
    application_id UUID NOT NULL REFERENCES applications(application_id),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

ALTER TABLE applicant_group_bookings ADD CONSTRAINT ex_applicant_group_time
    EXCLUDE USING gist (
        application_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (active);

CREATE TABLE group_evaluator_assignments (
    assignment_id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES evaluation_groups(group_id),
    evaluator_id UUID NOT NULL REFERENCES actors(actor_id),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REPLACED','CANCELLED')),
    assigned_by UUID NOT NULL REFERENCES actors(actor_id),
    version BIGINT NOT NULL DEFAULT 0,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at TIMESTAMPTZ,
    UNIQUE (group_id, evaluator_id, status)
);

CREATE TABLE evaluator_group_bookings (
    booking_id UUID PRIMARY KEY,
    assignment_id UUID NOT NULL UNIQUE REFERENCES group_evaluator_assignments(assignment_id),
    evaluator_id UUID NOT NULL REFERENCES actors(actor_id),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

ALTER TABLE evaluator_group_bookings ADD CONSTRAINT ex_evaluator_group_time
    EXCLUDE USING gist (
        evaluator_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (active);

CREATE TABLE group_assignment_history (
    history_id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES evaluation_groups(group_id),
    entity_type VARCHAR(24) NOT NULL CHECK (entity_type IN ('GROUP','MEMBER','EVALUATOR','ROOM','TIME')),
    entity_id UUID NOT NULL,
    action VARCHAR(48) NOT NULL,
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    reason_ciphertext TEXT,
    reason_iv VARCHAR(64),
    reason_wrapped_dek TEXT,
    reason_wrapped_dek_iv VARCHAR(64),
    reason_key_version VARCHAR(32),
    actor_id UUID NOT NULL REFERENCES actors(actor_id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evaluator_reports (
    report_id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES evaluation_groups(group_id),
    application_id UUID NOT NULL REFERENCES applications(application_id),
    evaluator_id UUID NOT NULL REFERENCES actors(actor_id),
    evaluation_template_version_id UUID NOT NULL REFERENCES evaluation_template_versions(evaluation_template_version_id),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','LOCKED','REOPENED','SUPERSEDED')),
    raw_score NUMERIC(10,4),
    maximum_score NUMERIC(10,4),
    version BIGINT NOT NULL DEFAULT 0,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (group_id, application_id, evaluator_id)
);

CREATE TABLE evaluator_report_responses (
    response_id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES evaluator_reports(report_id),
    criterion_id UUID NOT NULL REFERENCES evaluation_criteria(criterion_id),
    selected_option_id UUID REFERENCES evaluation_options(option_id),
    not_observed BOOLEAN NOT NULL DEFAULT FALSE,
    observed_value NUMERIC(10,4),
    version BIGINT NOT NULL DEFAULT 0,
    operation_id UUID NOT NULL UNIQUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (report_id, criterion_id),
    CHECK ((not_observed AND selected_option_id IS NULL AND observed_value IS NULL)
        OR (NOT not_observed AND selected_option_id IS NOT NULL AND observed_value IS NOT NULL))
);

CREATE TABLE evaluator_report_notes (
    note_id UUID PRIMARY KEY,
    report_id UUID NOT NULL UNIQUE REFERENCES evaluator_reports(report_id),
    ciphertext TEXT NOT NULL,
    iv VARCHAR(64) NOT NULL,
    wrapped_dek TEXT NOT NULL,
    wrapped_dek_iv VARCHAR(64) NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    operation_id UUID NOT NULL UNIQUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE report_edit_extensions (
    extension_id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES evaluator_reports(report_id),
    valid_until TIMESTAMPTZ NOT NULL,
    reason_ciphertext TEXT NOT NULL,
    reason_iv VARCHAR(64) NOT NULL,
    reason_wrapped_dek TEXT NOT NULL,
    reason_wrapped_dek_iv VARCHAR(64) NOT NULL,
    reason_key_version VARCHAR(32) NOT NULL,
    granted_by UUID NOT NULL REFERENCES actors(actor_id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE application_decisions_v2 (
    decision_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    decision VARCHAR(24) NOT NULL CHECK (decision IN ('ACCEPTED','REJECTED','WAITLIST')),
    note_ciphertext TEXT NOT NULL,
    note_iv VARCHAR(64) NOT NULL,
    note_wrapped_dek TEXT NOT NULL,
    note_wrapped_dek_iv VARCHAR(64) NOT NULL,
    note_key_version VARCHAR(32) NOT NULL,
    version INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SCHEDULED','PUBLISHED','CORRECTED')),
    decided_by UUID NOT NULL REFERENCES actors(actor_id),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    correction_of UUID REFERENCES application_decisions_v2(decision_id),
    correction_reason_ciphertext TEXT,
    correction_reason_iv VARCHAR(64),
    correction_reason_wrapped_dek TEXT,
    correction_reason_wrapped_dek_iv VARCHAR(64),
    correction_reason_key_version VARCHAR(32),
    UNIQUE (application_id, version)
);

CREATE UNIQUE INDEX uq_current_application_decision
    ON application_decisions_v2(application_id) WHERE status IN ('DRAFT','SCHEDULED','PUBLISHED');

CREATE TABLE publication_batches (
    batch_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    scheduled_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','PROCESSING','PUBLISHED','CANCELLED','PARTIAL')),
    version BIGINT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL REFERENCES actors(actor_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE TABLE publication_batch_items (
    item_id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES publication_batches(batch_id),
    application_id UUID NOT NULL REFERENCES applications(application_id),
    decision_id UUID NOT NULL REFERENCES application_decisions_v2(decision_id),
    decision_snapshot JSONB NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PUBLISHED','EMAIL_PENDING','EMAIL_SENT','EMAIL_FAILED')),
    published_at TIMESTAMPTZ,
    UNIQUE (batch_id, application_id)
);

ALTER TABLE notification_intents
    ADD COLUMN IF NOT EXISTS batch_id UUID REFERENCES publication_batches(batch_id),
    ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_error_code VARCHAR(64);

CREATE INDEX idx_waves_active ON process_waves(process_id, opens_at, closes_at, status);
CREATE INDEX idx_applications_wave ON applications(wave_id, eligibility_status, status);
CREATE INDEX idx_groups_schedule ON evaluation_groups(process_id, starts_at, room_id, status);
CREATE INDEX idx_group_members_application ON evaluation_group_members(application_id, status);
CREATE INDEX idx_group_evaluators_actor ON group_evaluator_assignments(evaluator_id, status);
CREATE INDEX idx_reports_evaluator ON evaluator_reports(evaluator_id, status, updated_at DESC);
CREATE INDEX idx_reports_application ON evaluator_reports(application_id, status);
CREATE INDEX idx_publication_due ON publication_batches(scheduled_at) WHERE status = 'SCHEDULED';

CREATE TRIGGER trg_group_assignment_history_append_only
    BEFORE UPDATE OR DELETE ON group_assignment_history
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_publication_batch_items_append_only
    BEFORE DELETE ON publication_batch_items
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE OR REPLACE FUNCTION prevent_published_rubric_version_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'PUBLISHED_RUBRIC_IMMUTABLE';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_published_criterion_mutation()
RETURNS TRIGGER AS $$
DECLARE version_id UUID;
BEGIN
    version_id := OLD.evaluation_template_version_id;
    IF EXISTS (SELECT 1 FROM evaluation_template_versions v
               WHERE v.evaluation_template_version_id = version_id AND v.status = 'PUBLISHED') THEN
        RAISE EXCEPTION 'PUBLISHED_RUBRIC_IMMUTABLE';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_published_option_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM evaluation_criteria c JOIN evaluation_template_versions v
                 ON v.evaluation_template_version_id = c.evaluation_template_version_id
               WHERE c.criterion_id = OLD.criterion_id AND v.status = 'PUBLISHED') THEN
        RAISE EXCEPTION 'PUBLISHED_RUBRIC_IMMUTABLE';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_published_rubric_version_immutable
    BEFORE UPDATE OR DELETE ON evaluation_template_versions
    FOR EACH ROW EXECUTE FUNCTION prevent_published_rubric_version_mutation();
CREATE TRIGGER trg_published_rubric_criteria_immutable
    BEFORE UPDATE OR DELETE ON evaluation_criteria
    FOR EACH ROW EXECUTE FUNCTION prevent_published_criterion_mutation();
CREATE TRIGGER trg_published_rubric_options_immutable
    BEFORE UPDATE OR DELETE ON evaluation_options
    FOR EACH ROW EXECUTE FUNCTION prevent_published_option_mutation();
