-- Pagos y formulario complementario permanecen físicamente en la base aislada de Prekínder.
ALTER TABLE applications
    ADD COLUMN payment_required BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN payment_status VARCHAR(32) NOT NULL DEFAULT 'UNPAID',
    ADD COLUMN paid_at TIMESTAMPTZ;

CREATE TABLE prekinder_application_school_syncs (
    sync_id UUID PRIMARY KEY,
    application_id UUID NOT NULL UNIQUE REFERENCES applications(application_id),
    business_partner_id BIGINT,
    business_partner_location_id BIGINT,
    student_user_id BIGINT,
    toku_customer_id VARCHAR(160),
    toku_subscription_id VARCHAR(160),
    sync_status VARCHAR(32) NOT NULL,
    guardian_state VARCHAR(64),
    customer_state VARCHAR(64),
    student_state VARCHAR(64),
    subscription_state VARCHAR(64),
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE prekinder_payments (
    payment_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(application_id),
    guardian_actor_id UUID NOT NULL REFERENCES actors(actor_id),
    provider VARCHAR(64) NOT NULL,
    provider_invoice_id VARCHAR(160),
    provider_transaction_id VARCHAR(160),
    institutional_charge_id BIGINT UNIQUE,
    idempotency_key VARCHAR(180) NOT NULL UNIQUE,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(8) NOT NULL,
    status VARCHAR(32) NOT NULL,
    checkout_url TEXT,
    expires_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    paid_amount NUMERIC(12,2),
    voucher TEXT,
    payment_method VARCHAR(80),
    external_status VARCHAR(40),
    last_status_checked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_prekinder_payments_application
    ON prekinder_payments(application_id, created_at DESC);

CREATE TABLE prekinder_payment_events (
    event_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES prekinder_payments(payment_id),
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE prekinder_complementary_forms (
    form_id UUID PRIMARY KEY,
    application_id UUID NOT NULL UNIQUE REFERENCES applications(application_id),
    ciphertext TEXT NOT NULL,
    iv VARCHAR(64) NOT NULL,
    wrapped_dek TEXT NOT NULL,
    wrapped_dek_iv VARCHAR(64) NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    submitted BOOLEAN NOT NULL DEFAULT FALSE,
    submitted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
