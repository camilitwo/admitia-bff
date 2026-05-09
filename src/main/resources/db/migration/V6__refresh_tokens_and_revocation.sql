-- V6: Refresh tokens rotativos + blacklist de jti para revocación de access tokens.
-- Ver docs/SECURITY_TOKENS.md secciones 4.3 y 4.5.

-- 1) refresh_tokens: cada refresh emitido (incluyendo los rotados/revocados).
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,         -- SHA-256 hex del refresh token (opaco)
    family_id VARCHAR(64) NOT NULL,                  -- agrupa la cadena de rotaciones para detección de robo
    parent_id BIGINT REFERENCES refresh_tokens(id),  -- el refresh anterior que rotó hacia este
    issued_at TIMESTAMP NOT NULL DEFAULT now(),
    expires_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP,
    revoked_at TIMESTAMP,
    revoked_reason VARCHAR(64),                      -- LOGOUT | ROTATED | STOLEN | EXPIRED | ADMIN
    user_agent VARCHAR(512),
    ip_address VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user      ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family    ON refresh_tokens(family_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires   ON refresh_tokens(expires_at);

-- 2) revoked_jtis: blacklist de access tokens (jti) revocados antes de su exp.
--    Se purgan automáticamente cuando expira el access token original.
CREATE TABLE IF NOT EXISTS revoked_jtis (
    jti VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    revoked_at TIMESTAMP NOT NULL DEFAULT now(),
    expires_at TIMESTAMP NOT NULL,
    reason VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_revoked_jtis_expires ON revoked_jtis(expires_at);

-- 3) Refuerzos en active_sessions (last_activity ya existe; añadimos jti opcional para correlacionar).
ALTER TABLE active_sessions ADD COLUMN IF NOT EXISTS jti VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_active_sessions_jti ON active_sessions(jti);

