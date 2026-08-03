BEGIN;

-- PARÁMETROS: editar únicamente esta fila antes de ejecutar.
-- Recomendado: conservar todo el personal y sus configuraciones.
CREATE TEMP TABLE admitia_cleanup_parameters (
    confirmation text NOT NULL,
    keep_staff boolean NOT NULL,
    keep_admin_email text,
    allow_financial_delete boolean NOT NULL
) ON COMMIT DROP;

INSERT INTO admitia_cleanup_parameters (
    confirmation,
    keep_staff,
    keep_admin_email,
    allow_financial_delete
) VALUES (
    'NO',  -- Cambiar exactamente a: RESET_ADMITIA_OPERATIONAL_DATA
    true,  -- false elimina personal/configuración y conserva sólo keep_admin_email
    NULL,  -- Obligatorio y con rol ADMIN activo cuando keep_staff = false
    false  -- true sólo si se autorizó eliminar pagos/cargos reales
);

SELECT set_config('admitia.cleanup_confirm', confirmation, true),
       set_config('admitia.keep_staff', keep_staff::text, true),
       set_config('admitia.keep_admin_email', lower(trim(COALESCE(keep_admin_email, ''))), true),
       set_config('admitia.allow_financial_delete', allow_financial_delete::text, true)
  FROM admitia_cleanup_parameters;

-- Evita dos limpiezas simultáneas y espera a que terminen escrituras activas.
SELECT pg_advisory_xact_lock(hashtext('admitia-operational-data-reset'));

DO $cleanup_preflight$
DECLARE
    expected_tables text[] := ARRAY[
        'active_sessions', 'application_school_syncs', 'applications',
        'complementary_forms', 'documents', 'email_verification_codes',
        'evaluations', 'flyway_schema_history', 'grade_availability',
        'guardians', 'interviewer_pair_grades', 'interviewer_pairs',
        'interviewer_schedules', 'interviews', 'notifications', 'parents',
        'payment_events', 'payments', 'refresh_tokens', 'revoked_jtis',
        'students', 'supporters', 'users'
    ];
    missing_tables text[];
    unknown_tables text[];
    keep_staff_value text := current_setting('admitia.keep_staff');
    keep_admin_email_value text := current_setting('admitia.keep_admin_email');
BEGIN
    IF current_setting('admitia.cleanup_confirm') <> 'RESET_ADMITIA_OPERATIONAL_DATA' THEN
        RAISE EXCEPTION 'Confirmación inválida. No se modificó nada.';
    END IF;

    IF keep_staff_value NOT IN ('true', 'false') THEN
        RAISE EXCEPTION 'keep_staff debe ser true o false; recibido: %', keep_staff_value;
    END IF;

    SELECT array_agg(name ORDER BY name)
      INTO missing_tables
      FROM unnest(expected_tables) AS expected(name)
     WHERE to_regclass('public.' || name) IS NULL;

    SELECT array_agg(table_name ORDER BY table_name)
      INTO unknown_tables
      FROM information_schema.tables
     WHERE table_schema = 'public'
       AND table_type = 'BASE TABLE'
       AND NOT (table_name = ANY (expected_tables));

    IF missing_tables IS NOT NULL THEN
        RAISE EXCEPTION 'Esquema incompatible. Faltan tablas: %', missing_tables;
    END IF;

    IF unknown_tables IS NOT NULL THEN
        RAISE EXCEPTION 'Esquema cambió; clasificar antes de limpiar: %', unknown_tables;
    END IF;

    IF to_regclass('prelaunch_backup.snapshot_metadata') IS NULL THEN
        RAISE EXCEPTION 'Falta el snapshot. Ejecutar 01_snapshot.sql antes de limpiar.';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM prelaunch_backup.snapshot_metadata
         WHERE database_name = current_database()
    ) THEN
        RAISE EXCEPTION 'El snapshot no corresponde a la base conectada.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM flyway_schema_history
         WHERE version = '19' AND success = true
    ) THEN
        RAISE EXCEPTION 'La migración Flyway V19 no está aplicada correctamente.';
    END IF;

    IF keep_staff_value = 'true' AND NOT EXISTS (
        SELECT 1 FROM users WHERE role = 'ADMIN' AND active = true
    ) THEN
        RAISE EXCEPTION 'No existe un ADMIN activo para conservar.';
    END IF;

    IF keep_staff_value = 'false' AND NOT EXISTS (
        SELECT 1
          FROM users
         WHERE lower(email) = keep_admin_email_value
           AND role = 'ADMIN'
           AND active = true
    ) THEN
        RAISE EXCEPTION 'keep_admin_email debe identificar un ADMIN activo existente.';
    END IF;

    IF current_setting('admitia.allow_financial_delete') = 'false' AND EXISTS (
        SELECT 1
          FROM payments
         WHERE status = 'PAID'
            OR paid_at IS NOT NULL
            OR paid_amount IS NOT NULL
            OR institutional_charge_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Hay pagos/cargos reales. Revisar y cambiar allow_financial_delete a true sólo con autorización.';
    END IF;
END
$cleanup_preflight$;

LOCK TABLE
    users,
    email_verification_codes,
    active_sessions,
    refresh_tokens,
    revoked_jtis,
    parents,
    guardians,
    supporters,
    students,
    applications,
    complementary_forms,
    documents,
    evaluations,
    interviews,
    notifications,
    payments,
    payment_events,
    application_school_syncs,
    interviewer_schedules,
    interviewer_pairs,
    interviewer_pair_grades
IN ACCESS EXCLUSIVE MODE;

-- Dependencias de postulaciones y pagos, desde las hojas hacia la raíz.
DELETE FROM payment_events;
DELETE FROM payments;
DELETE FROM application_school_syncs;
DELETE FROM documents;
DELETE FROM complementary_forms;
DELETE FROM evaluations;
DELETE FROM interviews;
DELETE FROM applications;

-- Personas asociadas al ciclo ya eliminado.
DELETE FROM guardians;
DELETE FROM parents;
DELETE FROM supporters;
DELETE FROM students;

-- Datos efímeros y trazas del período de pruebas.
DELETE FROM notifications;
DELETE FROM email_verification_codes;
DELETE FROM active_sessions;
DELETE FROM revoked_jtis;
DELETE FROM refresh_tokens;

-- En modo total las configuraciones que dependen del personal también salen.
DELETE FROM interviewer_pair_grades
 WHERE current_setting('admitia.keep_staff') = 'false';
DELETE FROM interviewer_pairs
 WHERE current_setting('admitia.keep_staff') = 'false';
DELETE FROM interviewer_schedules
 WHERE current_setting('admitia.keep_staff') = 'false';

-- Recomendado: sólo apoderados. Modo total: sólo queda el admin permitido.
DELETE FROM users
 WHERE (current_setting('admitia.keep_staff') = 'true' AND role = 'APODERADO')
    OR (current_setting('admitia.keep_staff') = 'false'
        AND lower(email) <> current_setting('admitia.keep_admin_email'));

-- Reinicia IDs sólo para tablas vacías. En users continúa desde el máximo
-- conservado para evitar colisiones con administrador/personal.
SELECT setval(pg_get_serial_sequence('users', 'id'),
              COALESCE(max(id), 1),
              max(id) IS NOT NULL)
  FROM users;

SELECT setval(pg_get_serial_sequence(table_name, 'id'), 1, false)
  FROM (VALUES
      ('email_verification_codes'), ('active_sessions'), ('refresh_tokens'),
      ('parents'), ('guardians'), ('supporters'), ('students'),
      ('applications'), ('complementary_forms'), ('documents'),
      ('evaluations'), ('interviews'), ('notifications'), ('payments'),
      ('payment_events'), ('application_school_syncs')
  ) AS cleared(table_name);

SELECT setval(pg_get_serial_sequence(table_name, 'id'), 1, false)
  FROM (VALUES ('interviewer_schedules'), ('interviewer_pairs')) AS cleared(table_name)
 WHERE current_setting('admitia.keep_staff') = 'false';

DO $cleanup_postcheck$
DECLARE
    remaining_operational bigint;
    keep_staff_value text := current_setting('admitia.keep_staff');
BEGIN
    SELECT
        (SELECT count(*) FROM applications)
      + (SELECT count(*) FROM students)
      + (SELECT count(*) FROM parents)
      + (SELECT count(*) FROM guardians)
      + (SELECT count(*) FROM supporters)
      + (SELECT count(*) FROM complementary_forms)
      + (SELECT count(*) FROM documents)
      + (SELECT count(*) FROM evaluations)
      + (SELECT count(*) FROM interviews)
      + (SELECT count(*) FROM payments)
      + (SELECT count(*) FROM payment_events)
      + (SELECT count(*) FROM application_school_syncs)
      + (SELECT count(*) FROM notifications)
      + (SELECT count(*) FROM email_verification_codes)
      + (SELECT count(*) FROM active_sessions)
      + (SELECT count(*) FROM refresh_tokens)
      + (SELECT count(*) FROM revoked_jtis)
      INTO remaining_operational;

    IF remaining_operational <> 0 THEN
        RAISE EXCEPTION 'Validación falló: quedan % filas operacionales.', remaining_operational;
    END IF;

    IF EXISTS (SELECT 1 FROM users WHERE role = 'APODERADO') THEN
        RAISE EXCEPTION 'Validación falló: todavía existen usuarios APODERADO.';
    END IF;

    IF keep_staff_value = 'false' AND (SELECT count(*) FROM users) <> 1 THEN
        RAISE EXCEPTION 'Validación falló: modo total debe conservar exactamente un usuario.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM users WHERE role = 'ADMIN' AND active = true) THEN
        RAISE EXCEPTION 'Validación falló: no quedó un ADMIN activo.';
    END IF;
END
$cleanup_postcheck$;

COMMIT;

ANALYZE users;
ANALYZE applications;
ANALYZE interviews;
ANALYZE evaluations;
ANALYZE payments;

SELECT 'Limpieza terminada. Ejecutar 04_validate.sql antes de iniciar el BFF.'
       AS result;
