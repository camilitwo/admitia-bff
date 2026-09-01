CREATE TABLE assessment_start_reminder_runs (
    scheduled_slot TIMESTAMPTZ PRIMARY KEY,
    assessment_date DATE NOT NULL,
    academic_year INTEGER NOT NULL,
    materialized_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE assessment_start_reminder_deliveries (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    scheduled_slot TIMESTAMPTZ NOT NULL,
    recipient VARCHAR(255),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    processing_started_at TIMESTAMPTZ,
    provider_message_id VARCHAR(160),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT ck_assessment_start_reminder_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'SKIPPED')),
    CONSTRAINT ux_assessment_start_reminder_slot
        UNIQUE (application_id, scheduled_slot)
);

CREATE INDEX ix_assessment_start_reminder_due
    ON assessment_start_reminder_deliveries (status, next_attempt_at, scheduled_slot);
