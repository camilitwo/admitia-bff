-- Cierre maestro del proceso de admision.
-- Migracion estrictamente aditiva: no modifica ni elimina datos existentes.

CREATE TABLE admission_cycles (
    id BIGSERIAL PRIMARY KEY,
    academic_year INTEGER NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    started_at TIMESTAMP,
    closed_at TIMESTAMP,
    closed_by_user_id BIGINT,
    total_applications BIGINT NOT NULL DEFAULT 0,
    queued_count BIGINT NOT NULL DEFAULT 0,
    sent_count BIGINT NOT NULL DEFAULT 0,
    failed_count BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_admission_cycles_year CHECK (academic_year BETWEEN 2000 AND 2200),
    CONSTRAINT ck_admission_cycles_status CHECK (
        status IN ('OPEN', 'PUBLISHING', 'CLOSED', 'CLOSED_WITH_ERRORS')
    ),
    CONSTRAINT fk_admission_cycles_closed_by
        FOREIGN KEY (closed_by_user_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE TABLE admission_result_dispatches (
    id BIGSERIAL PRIMARY KEY,
    cycle_id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    recipient_email VARCHAR(320) NOT NULL,
    recipient_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_at TIMESTAMP,
    lease_token VARCHAR(64),
    first_attempt_at TIMESTAMP,
    subject TEXT,
    body TEXT,
    idempotency_key VARCHAR(256) NOT NULL,
    provider_message_id VARCHAR(255),
    last_error TEXT,
    sent_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_admission_result_cycle_application UNIQUE (cycle_id, application_id),
    CONSTRAINT uq_admission_result_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_admission_result_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'UNKNOWN')
    ),
    CONSTRAINT ck_admission_result_attempts CHECK (attempts >= 0),
    CONSTRAINT fk_admission_result_cycle
        FOREIGN KEY (cycle_id) REFERENCES admission_cycles(id) ON DELETE RESTRICT,
    CONSTRAINT fk_admission_result_application
        FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE RESTRICT
);

CREATE INDEX idx_admission_result_dispatch_ready
    ON admission_result_dispatches (cycle_id, status, next_attempt_at, id);

CREATE INDEX idx_admission_result_dispatch_lease
    ON admission_result_dispatches (status, locked_at)
    WHERE status = 'PROCESSING';

-- Ciclo inicial solicitado. No se modifica applications.academic_year.
INSERT INTO admission_cycles (academic_year, status)
VALUES (2027, 'OPEN')
ON CONFLICT (academic_year) DO NOTHING;
