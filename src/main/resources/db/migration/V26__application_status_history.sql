CREATE TABLE application_status_history (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES applications(id),
    from_status VARCHAR(40) NOT NULL,
    to_status VARCHAR(40) NOT NULL,
    actor_id BIGINT NOT NULL REFERENCES users(id),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (from_status <> to_status)
);
CREATE INDEX idx_application_status_history_application
    ON application_status_history(application_id, changed_at, id);
