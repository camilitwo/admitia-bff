SELECT current_database() AS database,
       COALESCE(inet_server_addr()::text, 'local') AS server,
       CURRENT_TIMESTAMP AS validated_at;

SELECT *
  FROM (
    SELECT 'active_sessions' AS table_name, count(*) AS rows FROM active_sessions
    UNION ALL SELECT 'application_school_syncs', count(*) FROM application_school_syncs
    UNION ALL SELECT 'applications', count(*) FROM applications
    UNION ALL SELECT 'complementary_forms', count(*) FROM complementary_forms
    UNION ALL SELECT 'documents', count(*) FROM documents
    UNION ALL SELECT 'email_verification_codes', count(*) FROM email_verification_codes
    UNION ALL SELECT 'evaluations', count(*) FROM evaluations
    UNION ALL SELECT 'guardians', count(*) FROM guardians
    UNION ALL SELECT 'interviews', count(*) FROM interviews
    UNION ALL SELECT 'notifications', count(*) FROM notifications
    UNION ALL SELECT 'parents', count(*) FROM parents
    UNION ALL SELECT 'payment_events', count(*) FROM payment_events
    UNION ALL SELECT 'payments', count(*) FROM payments
    UNION ALL SELECT 'refresh_tokens', count(*) FROM refresh_tokens
    UNION ALL SELECT 'revoked_jtis', count(*) FROM revoked_jtis
    UNION ALL SELECT 'students', count(*) FROM students
    UNION ALL SELECT 'supporters', count(*) FROM supporters
    UNION ALL SELECT 'users/APODERADO', count(*) FROM users WHERE role = 'APODERADO'
  ) AS counts
 ORDER BY table_name;

DO $validate_clean_database$
DECLARE
    dirty_rows bigint;
BEGIN
    SELECT
        (SELECT count(*) FROM active_sessions)
      + (SELECT count(*) FROM application_school_syncs)
      + (SELECT count(*) FROM applications)
      + (SELECT count(*) FROM complementary_forms)
      + (SELECT count(*) FROM documents)
      + (SELECT count(*) FROM email_verification_codes)
      + (SELECT count(*) FROM evaluations)
      + (SELECT count(*) FROM guardians)
      + (SELECT count(*) FROM interviews)
      + (SELECT count(*) FROM notifications)
      + (SELECT count(*) FROM parents)
      + (SELECT count(*) FROM payment_events)
      + (SELECT count(*) FROM payments)
      + (SELECT count(*) FROM refresh_tokens)
      + (SELECT count(*) FROM revoked_jtis)
      + (SELECT count(*) FROM students)
      + (SELECT count(*) FROM supporters)
      + (SELECT count(*) FROM users WHERE role = 'APODERADO')
      INTO dirty_rows;

    IF dirty_rows <> 0 THEN
        RAISE EXCEPTION 'La limpieza no está completa: quedan % filas.', dirty_rows;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM users WHERE role = 'ADMIN' AND active = true) THEN
        RAISE EXCEPTION 'No existe ningún ADMIN activo.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM grade_availability) THEN
        RAISE EXCEPTION 'Se perdió la configuración de vacantes.';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM flyway_schema_history
         GROUP BY installed_rank
        HAVING bool_or(NOT success)
    ) THEN
        RAISE EXCEPTION 'Flyway contiene una migración fallida.';
    END IF;
END
$validate_clean_database$;

SELECT role, active, count(*) AS users
  FROM users
 GROUP BY role, active
 ORDER BY role, active DESC;

SELECT 'grade_availability' AS config, count(*) AS rows FROM grade_availability
UNION ALL SELECT 'interviewer_pairs', count(*) FROM interviewer_pairs
UNION ALL SELECT 'interviewer_pair_grades', count(*) FROM interviewer_pair_grades
UNION ALL SELECT 'interviewer_schedules', count(*) FROM interviewer_schedules;

SELECT 'Validación correcta: base operacional limpia y con ADMIN activo.' AS result;
