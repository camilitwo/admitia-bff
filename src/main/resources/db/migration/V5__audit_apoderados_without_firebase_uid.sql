-- V5: Inventario / saneamiento de identidades Firebase para apoderados.
-- Esta migración NO modifica datos. Sólo:
--   1) Crea una vista de soporte para auditar qué apoderados quedaron sin firebase_uid.
--   2) Refuerza el índice único existente sobre firebase_uid (idempotente).
-- El linking se hará desde la app vía /api/auth/firebase/link o /api/auth/firebase-login.

-- 1) Vista de auditoría: apoderados sin enlazar a Firebase.
CREATE OR REPLACE VIEW v_apoderados_sin_firebase AS
SELECT
    id,
    email,
    first_name,
    last_name,
    rut,
    phone,
    active,
    email_verified,
    last_login_at,
    created_at
FROM users
WHERE role = 'APODERADO'
  AND (firebase_uid IS NULL OR firebase_uid = '');

COMMENT ON VIEW v_apoderados_sin_firebase IS
    'Apoderados cuya cuenta local nunca quedó asociada a su firebase_uid. '
    'Deben enlazarse vía POST /api/auth/firebase/link o /api/auth/firebase-login.';

-- 2) Asegurar índice único (idempotente; en V3 ya se creó pero por defensa lo verificamos).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = current_schema()
          AND indexname = 'users_firebase_uid_key'
    ) THEN
        BEGIN
            ALTER TABLE users ADD CONSTRAINT users_firebase_uid_key UNIQUE (firebase_uid);
        EXCEPTION WHEN duplicate_table OR duplicate_object THEN
            NULL;
        END;
    END IF;
END $$;

