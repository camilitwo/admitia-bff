-- Base Prekinder nueva y autocontenida. Este archivo nunca se ejecuta sobre la base legacy.
CREATE TABLE schema_metadata (
    database_key VARCHAR(96) PRIMARY KEY,
    schema_purpose VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (database_key = 'admitia-prekinder')
);

INSERT INTO schema_metadata(database_key, schema_purpose)
VALUES ('admitia-prekinder', 'Admisión Prekínder aislada de la base legacy');

CREATE TABLE actors (
    actor_id UUID PRIMARY KEY,
    legacy_user_id BIGINT NOT NULL UNIQUE,
    role_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(160),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE admission_processes (
    process_id UUID PRIMARY KEY,
    academic_year INTEGER NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (academic_year, name)
);

CREATE TABLE workflow_stages (
    stage_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (process_id, code),
    UNIQUE (process_id, position)
);

CREATE TABLE families (
    family_id UUID PRIMARY KEY,
    external_reference VARCHAR(128),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE applicants (
    applicant_id UUID PRIMARY KEY,
    family_id UUID NOT NULL REFERENCES families(family_id),
    external_reference VARCHAR(128),
    identity_ciphertext TEXT NOT NULL,
    identity_iv VARCHAR(64) NOT NULL,
    identity_wrapped_dek TEXT NOT NULL,
    identity_wrapped_dek_iv VARCHAR(64) NOT NULL,
    identity_key_version VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE applications (
    application_id UUID PRIMARY KEY,
    applicant_id UUID NOT NULL REFERENCES applicants(applicant_id),
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    current_stage_id UUID REFERENCES workflow_stages(stage_id),
    status VARCHAR(48) NOT NULL,
    submitted_at TIMESTAMPTZ,
    submitted_by UUID REFERENCES actors(actor_id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (applicant_id, process_id)
);

CREATE TABLE application_state_history (
    history_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    from_status VARCHAR(48),
    to_status VARCHAR(48) NOT NULL,
    reason_code VARCHAR(64),
    actor_id UUID REFERENCES actors(actor_id),
    version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE family_members (
    member_id UUID PRIMARY KEY,
    family_id UUID NOT NULL REFERENCES families(family_id),
    relationship_code VARCHAR(48) NOT NULL,
    contact_ciphertext TEXT NOT NULL,
    contact_iv VARCHAR(64) NOT NULL,
    contact_wrapped_dek TEXT NOT NULL,
    contact_wrapped_dek_iv VARCHAR(64) NOT NULL,
    contact_key_version VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE form_templates (
    template_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    UNIQUE (process_id, code)
);

CREATE TABLE form_template_versions (
    template_version_id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES form_templates(template_id),
    version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    schema_document JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (template_id, version)
);

CREATE TABLE form_submissions (
    submission_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    template_version_id UUID NOT NULL REFERENCES form_template_versions(template_version_id),
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    submitted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (application_id, template_version_id)
);

CREATE TABLE application_declarations (
    declaration_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    declaration_code VARCHAR(96) NOT NULL,
    declaration_version INTEGER NOT NULL,
    accepted BOOLEAN NOT NULL,
    accepted_by UUID NOT NULL REFERENCES actors(actor_id),
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    withdrawn_at TIMESTAMPTZ,
    UNIQUE (application_id, declaration_code, declaration_version),
    CHECK (withdrawn_at IS NULL OR withdrawn_at >= accepted_at)
);

CREATE TABLE encrypted_field_values (
    field_value_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    field_code VARCHAR(96) NOT NULL,
    ciphertext TEXT NOT NULL,
    iv VARCHAR(64) NOT NULL,
    wrapped_dek TEXT NOT NULL,
    wrapped_dek_iv VARCHAR(64) NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by UUID NOT NULL REFERENCES actors(actor_id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (aggregate_type, aggregate_id, field_code)
);

CREATE TABLE inclusion_records (
    inclusion_id UUID PRIMARY KEY,
    application_id UUID NOT NULL UNIQUE REFERENCES applications(application_id),
    consent_status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inclusion_record_revisions (
    inclusion_revision_id UUID PRIMARY KEY,
    inclusion_id UUID NOT NULL REFERENCES inclusion_records(inclusion_id),
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    state VARCHAR(32) NOT NULL CHECK (state IN ('DRAFT','SUBMITTED','REVIEWED','WITHDRAWN')),
    ciphertext TEXT NOT NULL,
    iv VARCHAR(64) NOT NULL,
    wrapped_dek TEXT NOT NULL,
    wrapped_dek_iv VARCHAR(64) NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    authored_by UUID NOT NULL REFERENCES actors(actor_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (inclusion_id, revision_number)
);

CREATE TABLE schedules (
    schedule_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    name VARCHAR(160) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    capacity INTEGER NOT NULL CHECK (capacity > 0),
    CHECK (ends_at > starts_at)
);

CREATE TABLE schedule_rooms (
    room_id UUID PRIMARY KEY,
    schedule_id UUID NOT NULL REFERENCES schedules(schedule_id),
    code VARCHAR(64) NOT NULL,
    capacity INTEGER NOT NULL CHECK (capacity > 0),
    UNIQUE (schedule_id, code),
    UNIQUE (schedule_id, room_id)
);

CREATE TABLE schedule_slots (
    slot_id UUID PRIMARY KEY,
    schedule_id UUID NOT NULL REFERENCES schedules(schedule_id),
    room_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    capacity INTEGER NOT NULL CHECK (capacity > 0),
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED','CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (room_id, starts_at),
    UNIQUE (schedule_id, slot_id),
    FOREIGN KEY (schedule_id, room_id) REFERENCES schedule_rooms(schedule_id, room_id),
    CHECK (ends_at > starts_at)
);

CREATE TABLE schedule_assignments (
    assignment_id UUID PRIMARY KEY,
    schedule_id UUID NOT NULL REFERENCES schedules(schedule_id),
    slot_id UUID,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    group_code VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'ASSIGNED'
        CHECK (status IN ('ASSIGNED','CONFIRMED','ATTENDED','ABSENT','RESCHEDULED','CANCELLED')),
    replaces_assignment_id UUID REFERENCES schedule_assignments(assignment_id),
    version BIGINT NOT NULL DEFAULT 0,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (schedule_id, slot_id) REFERENCES schedule_slots(schedule_id, slot_id)
);

CREATE UNIQUE INDEX uq_active_schedule_assignment ON schedule_assignments(application_id)
    WHERE status IN ('ASSIGNED','CONFIRMED');

CREATE TABLE schedule_assignment_history (
    assignment_history_id UUID PRIMARY KEY,
    assignment_id UUID NOT NULL REFERENCES schedule_assignments(assignment_id),
    status VARCHAR(32) NOT NULL,
    actor_id UUID REFERENCES actors(actor_id),
    reason_code VARCHAR(64),
    version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (assignment_id, version)
);

CREATE TABLE evaluations (
    evaluation_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    schedule_assignment_id UUID REFERENCES schedule_assignments(assignment_id),
    evaluator_id UUID REFERENCES actors(actor_id),
    type_code VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    server_sequence BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evaluation_assignments (
    evaluation_assignment_id UUID PRIMARY KEY,
    evaluation_id UUID NOT NULL REFERENCES evaluations(evaluation_id),
    evaluator_id UUID NOT NULL REFERENCES actors(actor_id),
    specialty_code VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REPLACED','CANCELLED')),
    assigned_by UUID NOT NULL REFERENCES actors(actor_id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at TIMESTAMPTZ,
    CHECK (ended_at IS NULL OR ended_at >= assigned_at)
);

CREATE UNIQUE INDEX uq_active_evaluation_assignment ON evaluation_assignments(evaluation_id, specialty_code)
    WHERE status = 'ACTIVE';

CREATE TABLE evaluation_status_history (
    evaluation_history_id UUID PRIMARY KEY,
    evaluation_id UUID NOT NULL REFERENCES evaluations(evaluation_id),
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_id UUID REFERENCES actors(actor_id),
    reason_code VARCHAR(64),
    version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (evaluation_id, version)
);

CREATE TABLE evaluation_templates (
    evaluation_template_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    type_code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    UNIQUE (process_id, type_code)
);

CREATE TABLE evaluation_template_versions (
    evaluation_template_version_id UUID PRIMARY KEY,
    evaluation_template_id UUID NOT NULL REFERENCES evaluation_templates(evaluation_template_id),
    version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    maximum_score NUMERIC(10,4),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (evaluation_template_id, version)
);

ALTER TABLE evaluations
    ADD COLUMN evaluation_template_version_id UUID
        REFERENCES evaluation_template_versions(evaluation_template_version_id);

CREATE TABLE evaluation_criteria (
    criterion_id UUID PRIMARY KEY,
    evaluation_template_version_id UUID NOT NULL REFERENCES evaluation_template_versions(evaluation_template_version_id),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    descriptor TEXT NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    required BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (evaluation_template_version_id, code),
    UNIQUE (evaluation_template_version_id, position)
);

CREATE TABLE evaluation_options (
    option_id UUID PRIMARY KEY,
    criterion_id UUID NOT NULL REFERENCES evaluation_criteria(criterion_id),
    value NUMERIC(10,4) NOT NULL,
    label VARCHAR(160) NOT NULL,
    descriptor TEXT NOT NULL,
    professionally_validated BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL CHECK (position >= 0),
    UNIQUE (criterion_id, position),
    UNIQUE (criterion_id, option_id)
);

CREATE TABLE evaluation_responses (
    response_id UUID PRIMARY KEY,
    evaluation_id UUID NOT NULL REFERENCES evaluations(evaluation_id),
    criterion_id UUID NOT NULL REFERENCES evaluation_criteria(criterion_id),
    selected_option_id UUID NOT NULL REFERENCES evaluation_options(option_id),
    observed_value NUMERIC(10,4) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by UUID NOT NULL REFERENCES actors(actor_id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (evaluation_id, criterion_id),
    FOREIGN KEY (criterion_id, selected_option_id) REFERENCES evaluation_options(criterion_id, option_id)
);

CREATE TABLE evaluation_result_versions (
    result_version_id UUID PRIMARY KEY,
    evaluation_id UUID NOT NULL REFERENCES evaluations(evaluation_id),
    result_version INTEGER NOT NULL,
    template_version_id UUID NOT NULL REFERENCES evaluation_template_versions(evaluation_template_version_id),
    raw_score NUMERIC(10,4),
    maximum_score NUMERIC(10,4),
    percentage NUMERIC(10,6),
    completion_state VARCHAR(32) NOT NULL,
    snapshot JSONB NOT NULL,
    completed_by UUID REFERENCES actors(actor_id),
    completed_at TIMESTAMPTZ,
    UNIQUE (evaluation_id, result_version),
    CHECK (completion_state IN ('COMPLETED','ABSENT','NOT_EVALUABLE','CANCELLED')),
    CHECK ((completion_state = 'COMPLETED') OR (raw_score IS NULL AND percentage IS NULL))
);

CREATE TABLE evaluation_reopenings (
    reopening_id UUID PRIMARY KEY,
    evaluation_id UUID NOT NULL REFERENCES evaluations(evaluation_id),
    prior_result_version_id UUID NOT NULL REFERENCES evaluation_result_versions(result_version_id),
    reason_ciphertext TEXT NOT NULL,
    reason_iv VARCHAR(64) NOT NULL,
    reason_wrapped_dek TEXT NOT NULL,
    reason_wrapped_dek_iv VARCHAR(64) NOT NULL,
    reason_key_version VARCHAR(32) NOT NULL,
    authorized_by UUID NOT NULL REFERENCES actors(actor_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evaluation_fields (
    field_id UUID PRIMARY KEY,
    evaluation_id UUID NOT NULL REFERENCES evaluations(evaluation_id),
    field_code VARCHAR(96) NOT NULL,
    ciphertext TEXT NOT NULL,
    iv VARCHAR(64) NOT NULL,
    wrapped_dek TEXT NOT NULL,
    wrapped_dek_iv VARCHAR(64) NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by UUID NOT NULL REFERENCES actors(actor_id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (evaluation_id, field_code)
);

CREATE TABLE comments (
    comment_id UUID PRIMARY KEY,
    evaluation_id UUID NOT NULL REFERENCES evaluations(evaluation_id),
    author_id UUID NOT NULL REFERENCES actors(actor_id),
    operation_id UUID NOT NULL UNIQUE,
    server_sequence BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_revision INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (evaluation_id, server_sequence)
);

CREATE TABLE comment_revisions (
    revision_id UUID PRIMARY KEY,
    comment_id UUID NOT NULL REFERENCES comments(comment_id),
    revision_number INTEGER NOT NULL,
    base_revision INTEGER,
    state VARCHAR(32) NOT NULL,
    ciphertext TEXT NOT NULL,
    iv VARCHAR(64) NOT NULL,
    wrapped_dek TEXT NOT NULL,
    wrapped_dek_iv VARCHAR(64) NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    author_id UUID NOT NULL REFERENCES actors(actor_id),
    operation_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (comment_id, revision_number)
);

CREATE TABLE support_records (
    support_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    kind VARCHAR(48) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE support_record_revisions (
    support_revision_id UUID PRIMARY KEY,
    support_id UUID NOT NULL REFERENCES support_records(support_id),
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    state VARCHAR(32) NOT NULL CHECK (state IN ('DRAFT','COMPLETED','REOPENED','SUPERSEDED')),
    ciphertext TEXT NOT NULL,
    iv VARCHAR(64) NOT NULL,
    wrapped_dek TEXT NOT NULL,
    wrapped_dek_iv VARCHAR(64) NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    authored_by UUID NOT NULL REFERENCES actors(actor_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (support_id, revision_number)
);

CREATE TABLE referrals (
    referral_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    source_type VARCHAR(48) NOT NULL,
    source_id UUID NOT NULL,
    target_type VARCHAR(32) NOT NULL CHECK (target_type IN ('LEARNING_SUPPORT','DAP')),
    status VARCHAR(32) NOT NULL,
    assigned_actor_id UUID REFERENCES actors(actor_id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_active_referral_target ON referrals(application_id, target_type)
    WHERE status NOT IN ('REJECTED','CANCELLED','COMPLETED');

CREATE TABLE referral_revisions (
    referral_revision_id UUID PRIMARY KEY,
    referral_id UUID NOT NULL REFERENCES referrals(referral_id),
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    status VARCHAR(32) NOT NULL,
    rationale_ciphertext TEXT NOT NULL,
    rationale_iv VARCHAR(64) NOT NULL,
    rationale_wrapped_dek TEXT NOT NULL,
    rationale_wrapped_dek_iv VARCHAR(64) NOT NULL,
    rationale_key_version VARCHAR(32) NOT NULL,
    actor_id UUID NOT NULL REFERENCES actors(actor_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (referral_id, revision_number)
);

CREATE TABLE consent_records (
    consent_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    consent_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    accepted_at TIMESTAMPTZ,
    withdrawn_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (application_id, consent_type, version)
);

CREATE TABLE document_metadata (
    document_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    category VARCHAR(64) NOT NULL,
    storage_key VARCHAR(256) NOT NULL UNIQUE,
    media_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 VARCHAR(64) NOT NULL,
    scan_status VARCHAR(32) NOT NULL,
    restricted BOOLEAN NOT NULL DEFAULT FALSE,
    uploaded_by UUID NOT NULL REFERENCES actors(actor_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE scoring_policies (
    scoring_policy_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    applicant_weight NUMERIC(7,6) NOT NULL,
    family_weight NUMERIC(7,6) NOT NULL,
    formula_document JSONB NOT NULL,
    published_at TIMESTAMPTZ,
    UNIQUE (process_id, version),
    CHECK (applicant_weight >= 0 AND family_weight >= 0),
    CHECK (applicant_weight + family_weight = 1)
);

CREATE TABLE application_score_snapshots (
    score_snapshot_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    scoring_policy_id UUID NOT NULL REFERENCES scoring_policies(scoring_policy_id),
    snapshot_version INTEGER NOT NULL,
    applicant_result NUMERIC(10,6),
    family_result NUMERIC(10,6),
    integral_result NUMERIC(10,6),
    components JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (application_id, snapshot_version)
);

CREATE TABLE committee_dossiers (
    dossier_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    dossier_version INTEGER NOT NULL CHECK (dossier_version > 0),
    score_snapshot_id UUID REFERENCES application_score_snapshots(score_snapshot_id),
    completeness_state VARCHAR(32) NOT NULL CHECK (completeness_state IN ('INCOMPLETE','READY','STALE')),
    snapshot JSONB NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (application_id, dossier_version)
);

CREATE TABLE committee_decisions (
    decision_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    dossier_id UUID NOT NULL REFERENCES committee_dossiers(dossier_id),
    decision VARCHAR(32) NOT NULL CHECK (decision IN ('OFFERED','WAITLISTED','NOT_ADMITTED','REQUIRES_REVIEW')),
    rationale_ciphertext TEXT NOT NULL,
    rationale_iv VARCHAR(64) NOT NULL,
    rationale_wrapped_dek TEXT NOT NULL,
    rationale_wrapped_dek_iv VARCHAR(64) NOT NULL,
    rationale_key_version VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    decided_by UUID NOT NULL REFERENCES actors(actor_id),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (dossier_id)
);

CREATE TABLE committee_review_tasks (
    task_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    source_decision_id UUID NOT NULL REFERENCES committee_decisions(decision_id),
    reason_ciphertext TEXT NOT NULL,
    reason_iv VARCHAR(64) NOT NULL,
    reason_wrapped_dek TEXT NOT NULL,
    reason_wrapped_dek_iv VARCHAR(64) NOT NULL,
    reason_key_version VARCHAR(32) NOT NULL,
    assigned_actor_id UUID REFERENCES actors(actor_id),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE offers (
    offer_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE offer_status_history (
    offer_history_id UUID PRIMARY KEY,
    offer_id UUID NOT NULL REFERENCES offers(offer_id),
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL CHECK (to_status IN ('OFFERED','ACCEPTED','DECLINED','EXPIRED','CANCELLED')),
    actor_id UUID REFERENCES actors(actor_id),
    reason_code VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE restricted_case_access_grants (
    grant_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    actor_id UUID NOT NULL REFERENCES actors(actor_id),
    resource_type VARCHAR(48) NOT NULL CHECK (resource_type IN ('INCLUSION','DAP','PROFESSIONAL_DOCUMENT','DIAGNOSTIC','CONSENT')),
    permission VARCHAR(16) NOT NULL CHECK (permission IN ('READ','WRITE','DOWNLOAD')),
    granted_by UUID NOT NULL REFERENCES actors(actor_id),
    valid_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    reason_code VARCHAR(64) NOT NULL,
    CHECK (valid_until IS NULL OR valid_until > valid_from),
    CHECK (revoked_at IS NULL OR revoked_at >= valid_from)
);

CREATE UNIQUE INDEX uq_active_restricted_grant
    ON restricted_case_access_grants(application_id, actor_id, resource_type, permission)
    WHERE revoked_at IS NULL;

CREATE TABLE audit_events (
    audit_id UUID PRIMARY KEY,
    actor_id UUID,
    action VARCHAR(96) NOT NULL,
    aggregate_type VARCHAR(64),
    aggregate_id UUID,
    result VARCHAR(32) NOT NULL,
    origin_hash VARCHAR(64),
    request_id VARCHAR(96),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    UNIQUE (aggregate_id, sequence, event_type),
    CHECK (sequence >= 0),
    CHECK (payload = '{}'::jsonb)
);

CREATE TABLE processed_operations (
    operation_id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES actors(actor_id),
    operation_type VARCHAR(64) NOT NULL,
    result_reference UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE admission_processes
    ADD CONSTRAINT ck_admission_process_status
        CHECK (status IN ('DRAFT','PUBLISHED','CLOSED','ARCHIVED')),
    ADD CONSTRAINT ck_admission_process_dates
        CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at);

ALTER TABLE applications
    ADD CONSTRAINT ck_application_status CHECK (status IN (
        'DRAFT','SUBMITTED','UNDER_REVIEW','REQUIRES_INFORMATION','SCHEDULED','IN_EVALUATION',
        'READY_FOR_COMMITTEE','COMMITTEE_REVIEW','OFFERED','WAITLISTED','NOT_ADMITTED',
        'REQUIRES_REVIEW','ACCEPTED','DECLINED','CANCELLED'
    ));

ALTER TABLE evaluations
    ADD CONSTRAINT ck_evaluation_status CHECK (status IN (
        'PENDING','IN_PROGRESS','DRAFT_SAVED','COMPLETED','ABSENT','NOT_EVALUABLE',
        'RESCHEDULE_REQUIRED','REOPENED','CANCELLED'
    ));

ALTER TABLE comments
    ADD CONSTRAINT ck_comment_status CHECK (status IN ('ACTIVE','DELETED')),
    ADD CONSTRAINT ck_comment_revision_positive CHECK (current_revision > 0),
    ADD CONSTRAINT ck_comment_sequence_positive CHECK (server_sequence > 0);

ALTER TABLE comment_revisions
    ADD CONSTRAINT ck_comment_revision_state CHECK (state IN ('CURRENT','CONFLICTED','TOMBSTONE')),
    ADD CONSTRAINT ck_comment_revision_number CHECK (revision_number > 0),
    ADD CONSTRAINT ck_comment_base_revision CHECK (base_revision IS NULL OR base_revision > 0);

ALTER TABLE support_records
    ADD CONSTRAINT ck_support_kind CHECK (kind IN ('LEARNING_SUPPORT','DAP')),
    ADD CONSTRAINT ck_support_status CHECK (status IN ('DRAFT','ASSIGNED','IN_PROGRESS','COMPLETED','REOPENED','CANCELLED'));

ALTER TABLE referrals
    ADD CONSTRAINT ck_referral_status CHECK (status IN (
        'SUGGESTED','UNDER_REVIEW','APPROVED','REJECTED','REQUIRES_INFORMATION',
        'ASSIGNED','IN_PROGRESS','COMPLETED','CANCELLED'
    ));

ALTER TABLE offers
    ADD CONSTRAINT ck_offer_status CHECK (status IN ('OFFERED','ACCEPTED','DECLINED','EXPIRED','CANCELLED'));

CREATE TABLE notification_intents (
    notification_id UUID PRIMARY KEY,
    application_id UUID REFERENCES applications(application_id),
    recipient_actor_id UUID REFERENCES actors(actor_id),
    template_code VARCHAR(64) NOT NULL,
    channel VARCHAR(24) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ
);

CREATE INDEX idx_applications_stage ON applications(current_stage_id, status);
CREATE UNIQUE INDEX uq_applicant_external_reference ON applicants(external_reference)
    WHERE external_reference IS NOT NULL;
CREATE UNIQUE INDEX uq_family_external_reference ON families(external_reference)
    WHERE external_reference IS NOT NULL;
CREATE INDEX idx_evaluations_application ON evaluations(application_id, updated_at DESC);
CREATE INDEX idx_application_history ON application_state_history(application_id, occurred_at DESC);
CREATE INDEX idx_schedule_slots_open ON schedule_slots(schedule_id, starts_at) WHERE status = 'OPEN';
CREATE INDEX idx_schedule_assignments_slot ON schedule_assignments(slot_id, status);
CREATE INDEX idx_evaluation_assignments_actor ON evaluation_assignments(evaluator_id, status, assigned_at);
CREATE INDEX idx_evaluation_status_history ON evaluation_status_history(evaluation_id, occurred_at DESC);
CREATE INDEX idx_referrals_application ON referrals(application_id, target_type, status);
CREATE INDEX idx_support_application ON support_records(application_id, kind, status);
CREATE INDEX idx_documents_application ON document_metadata(application_id, restricted, created_at DESC);
CREATE INDEX idx_comments_evaluation_sequence ON comments(evaluation_id, server_sequence);
CREATE INDEX idx_comment_revisions_comment ON comment_revisions(comment_id, revision_number);
CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at) WHERE published_at IS NULL;
CREATE INDEX idx_audit_aggregate ON audit_events(aggregate_type, aggregate_id, occurred_at DESC);
CREATE INDEX idx_restricted_grants_actor ON restricted_case_access_grants(actor_id, application_id, resource_type)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_notification_pending ON notification_intents(created_at) WHERE sent_at IS NULL;

CREATE FUNCTION prevent_append_only_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'append-only relation cannot be modified';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_comment_revisions_append_only
    BEFORE UPDATE OR DELETE ON comment_revisions
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_evaluation_results_append_only
    BEFORE UPDATE OR DELETE ON evaluation_result_versions
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_application_history_append_only
    BEFORE UPDATE OR DELETE ON application_state_history
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_inclusion_revisions_append_only
    BEFORE UPDATE OR DELETE ON inclusion_record_revisions
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_schedule_assignment_history_append_only
    BEFORE UPDATE OR DELETE ON schedule_assignment_history
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_evaluation_status_history_append_only
    BEFORE UPDATE OR DELETE ON evaluation_status_history
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_support_revisions_append_only
    BEFORE UPDATE OR DELETE ON support_record_revisions
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_referral_revisions_append_only
    BEFORE UPDATE OR DELETE ON referral_revisions
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_committee_decisions_append_only
    BEFORE UPDATE OR DELETE ON committee_decisions
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();

CREATE TRIGGER trg_offer_history_append_only
    BEFORE UPDATE OR DELETE ON offer_status_history
    FOR EACH ROW EXECUTE FUNCTION prevent_append_only_mutation();
