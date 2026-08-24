CREATE TABLE application_reminder_runs (
    scheduled_slot TIMESTAMPTZ PRIMARY KEY,
    scheduled_date DATE NOT NULL,
    academic_year INTEGER NOT NULL,
    materialized_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE application_reminder_deliveries (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    reminder_type VARCHAR(60) NOT NULL,
    scheduled_slot TIMESTAMPTZ NOT NULL,
    scheduled_date DATE NOT NULL,
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
    CONSTRAINT ck_application_reminder_type
        CHECK (reminder_type IN ('PAYMENT_REMINDER', 'FAMILY_REGISTRATION_REMINDER')),
    CONSTRAINT ck_application_reminder_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'SKIPPED')),
    CONSTRAINT ux_application_reminder_slot
        UNIQUE (application_id, reminder_type, scheduled_slot)
);

CREATE INDEX ix_application_reminder_due
    ON application_reminder_deliveries (status, next_attempt_at, scheduled_date);
