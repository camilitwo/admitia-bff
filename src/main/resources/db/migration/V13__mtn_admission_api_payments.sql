ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS institutional_charge_id BIGINT,
    ADD COLUMN IF NOT EXISTS paid_amount NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS voucher VARCHAR(255),
    ADD COLUMN IF NOT EXISTS payment_method VARCHAR(120),
    ADD COLUMN IF NOT EXISTS external_status VARCHAR(40),
    ADD COLUMN IF NOT EXISTS last_status_checked_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS ux_payments_institutional_charge_id
    ON payments (institutional_charge_id)
    WHERE institutional_charge_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS application_school_syncs (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL UNIQUE REFERENCES applications(id) ON DELETE CASCADE,
    sync_status VARCHAR(40) NOT NULL,
    business_partner_id BIGINT,
    business_partner_location_id BIGINT,
    student_user_id BIGINT,
    toku_customer_id VARCHAR(160),
    toku_subscription_id VARCHAR(160),
    guardian_state VARCHAR(80),
    customer_state VARCHAR(80),
    student_state VARCHAR(80),
    subscription_state VARCHAR(80),
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    last_attempt_at TIMESTAMP NOT NULL,
    last_success_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
