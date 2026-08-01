ALTER TABLE users
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN temporary_password_hash VARCHAR(255),
    ADD COLUMN temporary_password_expires_at TIMESTAMP,
    ADD COLUMN password_reset_at TIMESTAMP,
    ADD COLUMN password_reset_by BIGINT;

ALTER TABLE users
    ADD CONSTRAINT fk_users_password_reset_by
        FOREIGN KEY (password_reset_by) REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_users_temporary_password_expires_at
    ON users (temporary_password_expires_at)
    WHERE must_change_password = TRUE;
