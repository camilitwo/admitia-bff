SELECT current_database() AS database,
       current_user AS connected_as,
       COALESCE(inet_server_addr()::text, 'local') AS server,
       inet_server_port() AS port,
       CURRENT_TIMESTAMP AS inspected_at;

DO $preview_schema_check$
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
BEGIN
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
END
$preview_schema_check$;

SELECT installed_rank, version, description, installed_on, success
  FROM flyway_schema_history
 ORDER BY installed_rank DESC
 LIMIT 5;

SELECT *
  FROM (
    SELECT 'users' AS table_name, count(*) AS rows FROM users
    UNION ALL SELECT 'active_sessions', count(*) FROM active_sessions
    UNION ALL SELECT 'refresh_tokens', count(*) FROM refresh_tokens
    UNION ALL SELECT 'revoked_jtis', count(*) FROM revoked_jtis
    UNION ALL SELECT 'email_verification_codes', count(*) FROM email_verification_codes
    UNION ALL SELECT 'students', count(*) FROM students
    UNION ALL SELECT 'parents', count(*) FROM parents
    UNION ALL SELECT 'guardians', count(*) FROM guardians
    UNION ALL SELECT 'supporters', count(*) FROM supporters
    UNION ALL SELECT 'applications', count(*) FROM applications
    UNION ALL SELECT 'complementary_forms', count(*) FROM complementary_forms
    UNION ALL SELECT 'documents', count(*) FROM documents
    UNION ALL SELECT 'evaluations', count(*) FROM evaluations
    UNION ALL SELECT 'interviews', count(*) FROM interviews
    UNION ALL SELECT 'payments', count(*) FROM payments
    UNION ALL SELECT 'payment_events', count(*) FROM payment_events
    UNION ALL SELECT 'application_school_syncs', count(*) FROM application_school_syncs
    UNION ALL SELECT 'notifications', count(*) FROM notifications
    UNION ALL SELECT 'interviewer_schedules', count(*) FROM interviewer_schedules
    UNION ALL SELECT 'interviewer_pairs', count(*) FROM interviewer_pairs
    UNION ALL SELECT 'interviewer_pair_grades', count(*) FROM interviewer_pair_grades
    UNION ALL SELECT 'grade_availability', count(*) FROM grade_availability
  ) AS counts
 ORDER BY table_name;

SELECT role, active, count(*) AS users
  FROM users
 GROUP BY role, active
 ORDER BY role, active DESC;

SELECT id, email, role, active
  FROM users
 WHERE role <> 'APODERADO'
 ORDER BY role, email;

SELECT status,
       count(*) AS payments,
       COALESCE(sum(amount), 0) AS total_amount,
       COALESCE(sum(paid_amount), 0) AS total_paid,
       count(*) FILTER (WHERE institutional_charge_id IS NOT NULL) AS institutional_charges
  FROM payments
 GROUP BY status
 ORDER BY status;

SELECT count(*) AS documents,
       COALESCE(sum(file_size), 0) AS bytes,
       count(*) FILTER (WHERE file_path ~ '^https?://') AS remote_files,
       count(*) FILTER (WHERE file_path !~ '^https?://') AS local_files
  FROM documents;

SELECT role, count(*) AS firebase_users
  FROM users
 WHERE firebase_uid IS NOT NULL
 GROUP BY role
 ORDER BY role;

SELECT 'Vista previa terminada. Este script no modificó datos.' AS result;
