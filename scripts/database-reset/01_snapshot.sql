-- Snapshot lógico dentro de la misma base. El script aborta si ya existe para
-- impedir que un respaldo anterior sea sobrescrito accidentalmente.
BEGIN;

DO $snapshot_preflight$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'prelaunch_backup') THEN
        RAISE EXCEPTION
            'Ya existe el esquema prelaunch_backup. No se sobrescribió el respaldo.';
    END IF;
END
$snapshot_preflight$;

CREATE SCHEMA prelaunch_backup;
REVOKE ALL ON SCHEMA prelaunch_backup FROM PUBLIC;

CREATE TABLE prelaunch_backup.snapshot_metadata AS
SELECT current_database() AS database_name,
       current_user AS created_by,
       COALESCE(inet_server_addr()::text, 'local') AS server_address,
       inet_server_port() AS server_port,
       CURRENT_TIMESTAMP AS created_at;

CREATE TABLE prelaunch_backup.users AS TABLE public.users;
CREATE TABLE prelaunch_backup.email_verification_codes AS TABLE public.email_verification_codes;
CREATE TABLE prelaunch_backup.active_sessions AS TABLE public.active_sessions;
CREATE TABLE prelaunch_backup.refresh_tokens AS TABLE public.refresh_tokens;
CREATE TABLE prelaunch_backup.revoked_jtis AS TABLE public.revoked_jtis;
CREATE TABLE prelaunch_backup.parents AS TABLE public.parents;
CREATE TABLE prelaunch_backup.guardians AS TABLE public.guardians;
CREATE TABLE prelaunch_backup.supporters AS TABLE public.supporters;
CREATE TABLE prelaunch_backup.students AS TABLE public.students;
CREATE TABLE prelaunch_backup.applications AS TABLE public.applications;
CREATE TABLE prelaunch_backup.complementary_forms AS TABLE public.complementary_forms;
CREATE TABLE prelaunch_backup.documents AS TABLE public.documents;
CREATE TABLE prelaunch_backup.evaluations AS TABLE public.evaluations;
CREATE TABLE prelaunch_backup.interviews AS TABLE public.interviews;
CREATE TABLE prelaunch_backup.interviewer_schedules AS TABLE public.interviewer_schedules;
CREATE TABLE prelaunch_backup.interviewer_pairs AS TABLE public.interviewer_pairs;
CREATE TABLE prelaunch_backup.interviewer_pair_grades AS TABLE public.interviewer_pair_grades;
CREATE TABLE prelaunch_backup.notifications AS TABLE public.notifications;
CREATE TABLE prelaunch_backup.payments AS TABLE public.payments;
CREATE TABLE prelaunch_backup.payment_events AS TABLE public.payment_events;
CREATE TABLE prelaunch_backup.application_school_syncs AS TABLE public.application_school_syncs;
CREATE TABLE prelaunch_backup.grade_availability AS TABLE public.grade_availability;

-- Manifiestos para recursos que están fuera de PostgreSQL.
CREATE TABLE prelaunch_backup.document_file_manifest AS
SELECT id, application_id, file_name, file_path, file_size, content_type
  FROM public.documents;

CREATE TABLE prelaunch_backup.firebase_user_manifest AS
SELECT id, email, role, firebase_uid, active
  FROM public.users
 WHERE firebase_uid IS NOT NULL;

REVOKE ALL ON ALL TABLES IN SCHEMA prelaunch_backup FROM PUBLIC;

COMMIT;

SELECT * FROM prelaunch_backup.snapshot_metadata;

SELECT schemaname,
       relname AS snapshot_table,
       n_live_tup AS estimated_rows
  FROM pg_stat_user_tables
 WHERE schemaname = 'prelaunch_backup'
 ORDER BY relname;

SELECT 'Snapshot creado en prelaunch_backup. Se recomienda además un pg_dump externo.'
       AS result;
