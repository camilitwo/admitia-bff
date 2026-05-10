ALTER TABLE applications
    ADD COLUMN IF NOT EXISTS payment_status VARCHAR(40) NOT NULL DEFAULT 'UNPAID',
    ADD COLUMN IF NOT EXISTS payment_required BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS paid_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    guardian_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    provider VARCHAR(40) NOT NULL,
    provider_customer_id VARCHAR(100),
    provider_invoice_id VARCHAR(100),
    provider_transaction_id VARCHAR(100),
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    amount NUMERIC(12,2) NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'CLP',
    status VARCHAR(40) NOT NULL,
    checkout_url TEXT,
    expires_at TIMESTAMP,
    paid_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_payments_one_active_per_application
    ON payments (application_id)
    WHERE status = 'PAYMENT_PENDING';

CREATE INDEX IF NOT EXISTS ix_payments_provider_invoice_id
    ON payments (provider_invoice_id);

CREATE TABLE IF NOT EXISTS payment_events (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT REFERENCES payments(id) ON DELETE SET NULL,
    provider VARCHAR(40) NOT NULL,
    provider_event_id VARCHAR(120),
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_events_provider_event
    ON payment_events (provider, provider_event_id)
    WHERE provider_event_id IS NOT NULL;
