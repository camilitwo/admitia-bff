# Estrategia de performance PostgreSQL para Admitia BFF

Fecha de analisis: 2026-07-06

## Alcance revisado

Se revisaron las consultas reales del monolito `admitia-bff`, especialmente:

- Repositorios Spring Data JPA en `src/main/java/cl/mtn/admitiabff/repository`.
- Migraciones Flyway en `src/main/resources/db/migration`.
- Patrones de uso en servicios, con foco en listados, busquedas, dashboard, autenticacion, entrevistas, evaluaciones, documentos, pagos y notificaciones.

La carpeta `admitia-platform-nuevos-repos/admitia-platform-services` tiene servicios Spring Boot con PostgreSQL configurado, pero hoy no contiene repositorios/entidades/migraciones de negocio equivalentes. La optimizacion aplicable ahi es la misma regla base: todo indice compuesto debe partir por `tenant_id` cuando esas tablas existan.

## Diagnostico principal

1. Faltan indices para casi todas las foreign keys usadas en joins, deletes y cascadas: `applications.student_id`, `documents.application_id`, `evaluations.application_id`, `interviews.application_id`, etc.
2. Los listados principales ordenan por `submission_date`, `created_at`, `scheduled_date` y filtran por `status`, `deleted_at`, `is_archived`, `grade_applied`, `role`, `active`.
3. Las busquedas por texto usan `lower(col) like '%texto%'`. Eso no usa B-tree normal. Debe usarse `pg_trgm` con indices GIN funcionales.
4. Hay dashboards y reportes que cargan tablas completas con `findAll()` y luego agrupan en Java. Los indices ayudan, pero la mejora grande requiere mover esos agregados a SQL.
5. Hay conteos por fila en dashboards: por cada postulacion se cuentan documentos y entrevistas. Eso genera N+1 queries.
6. Para updates/deletes, el cuello principal no es el `UPDATE` en si, sino encontrar filas objetivo y validar/cascadear FKs sin indices.
7. Para inserts masivos o flujos con varios `save()`, falta batching Hibernate en configuracion.

## Orden recomendado

1. Ejecutar primero los comandos de observabilidad.
2. Crear indices concurrentes en produccion, fuera de una transaccion.
3. Ejecutar `ANALYZE`.
4. Medir con `EXPLAIN (ANALYZE, BUFFERS)` las consultas criticas.
5. Reescribir dashboards/reportes que hoy hacen `findAll()` o N+1.
6. Convertir los indices validados en una migracion Flyway futura.

## Comandos PostgreSQL seguros para ejecutar

> Importante: `CREATE INDEX CONCURRENTLY` no puede correr dentro de `BEGIN/COMMIT` ni dentro de una migracion Flyway transaccional normal. Ejecutar cada bloque desde `psql` o configurar la migracion como no transaccional.

### 1. Extensiones y estadisticas

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
```

Si `pg_stat_statements` no carga porque falta `shared_preload_libraries`, revisar primero el valor actual para no pisar otras extensiones:

```sql
SHOW shared_preload_libraries;
```

Luego habilitarlo a nivel servidor, conservando cualquier libreria existente en la lista, y reiniciar PostgreSQL:

```sql
-- Ejemplo si no habia librerias previas. Si habia otras, agregarlas separadas por coma.
ALTER SYSTEM SET shared_preload_libraries = 'pg_stat_statements';
ALTER SYSTEM SET pg_stat_statements.track = 'all';
ALTER SYSTEM SET track_io_timing = on;
SELECT pg_reload_conf();
```

### 2. Indices para `users`

Consultas cubiertas: login por email, Firebase, busqueda de usuarios, conteos por rol/activo, listados de staff/interviewers.

Validar antes de crear el indice unico funcional:

```sql
SELECT lower(email) AS normalized_email, count(*) AS total
FROM users
GROUP BY lower(email)
HAVING count(*) > 1;
```

```sql
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_users_lower_email
    ON users (lower(email));

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_users_rut
    ON users (rut)
    WHERE rut IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_users_role_name
    ON users (role, first_name, last_name);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_users_active_role_name
    ON users (active, role, last_name, first_name);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_users_first_name_trgm
    ON users USING gin (lower(first_name) gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_users_last_name_trgm
    ON users USING gin (lower(last_name) gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_users_email_trgm
    ON users USING gin (lower(email) gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_users_rut_trgm
    ON users USING gin (lower(coalesce(rut, '')) gin_trgm_ops);
```

### 3. Indices para sesiones y tokens

Consultas cubiertas: `findByTokenHash`, logout por usuario, revocacion por familia/usuario, purga por expiracion.

Validar antes de crear el indice unico:

```sql
SELECT token_hash, count(*) AS total
FROM active_sessions
GROUP BY token_hash
HAVING count(*) > 1;
```

```sql
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_active_sessions_token_hash
    ON active_sessions (token_hash);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_active_sessions_user_id
    ON active_sessions (user_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_refresh_tokens_family_active
    ON refresh_tokens (family_id)
    WHERE revoked_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_refresh_tokens_user_active
    ON refresh_tokens (user_id)
    WHERE revoked_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_refresh_tokens_expires_id
    ON refresh_tokens (expires_at, id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_revoked_jtis_expires_jti
    ON revoked_jtis (expires_at, jti);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_email_verification_email_code_active
    ON email_verification_codes (lower(email), code, used, expires_at DESC, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_email_verification_lower_email
    ON email_verification_codes (lower(email));
```

### 4. Indices para `students`

Consultas cubiertas: busqueda por nombre/RUT, filtro por curso, conteos por curso, validacion de RUT normalizado.

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_students_grade_name
    ON students (grade_applied, first_name, paternal_last_name);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_students_grade_id
    ON students (grade_applied, id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_students_rut
    ON students (rut)
    WHERE rut IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_students_normalized_rut
    ON students (upper(replace(replace(rut, '.', ''), '-', '')))
    WHERE rut IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_students_first_name_trgm
    ON students USING gin (lower(first_name) gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_students_paternal_last_name_trgm
    ON students USING gin (lower(coalesce(paternal_last_name, '')) gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_students_maternal_last_name_trgm
    ON students USING gin (lower(coalesce(maternal_last_name, '')) gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_students_rut_trgm
    ON students USING gin (lower(coalesce(rut, '')) gin_trgm_ops);
```

### 5. Indices para `applications`

Consultas cubiertas: listados admin activos, filtro por estado, postulaciones por apoderado, rangos por fecha, soft delete, archive, pagos y joins con estudiante.

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_applications_active_recent
    ON applications (submission_date DESC, id DESC)
    WHERE deleted_at IS NULL AND is_archived = false;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_applications_active_status_recent
    ON applications (status, submission_date DESC, id DESC)
    WHERE deleted_at IS NULL AND is_archived = false;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_applications_deleted_status_submission
    ON applications (deleted_at, status, submission_date DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_applications_student_active
    ON applications (student_id)
    WHERE deleted_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_applications_applicant_recent
    ON applications (applicant_user_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_applications_created_active
    ON applications (created_at)
    WHERE deleted_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_applications_payment_status
    ON applications (payment_status, paid_at)
    WHERE deleted_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_applications_fk_father
    ON applications (father_id)
    WHERE father_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_applications_fk_mother
    ON applications (mother_id)
    WHERE mother_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_applications_fk_guardian
    ON applications (guardian_id)
    WHERE guardian_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_applications_fk_supporter
    ON applications (supporter_id)
    WHERE supporter_id IS NOT NULL;
```

Indice opcional para busqueda por id cuando se usa `cast(ae.id as text) like '%texto%'`:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_applications_id_text_trgm
    ON applications USING gin ((id::text) gin_trgm_ops);
```

### 6. Indices para `parents`, `guardians` y `supporters`

Consultas cubiertas: busqueda de apoderados, filtro por relacion, joins desde postulaciones y deletes con FKs.

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_guardians_user_recent
    ON guardians (user_id, created_at DESC)
    WHERE user_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_guardians_rut
    ON guardians (rut)
    WHERE rut IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_guardians_relationship
    ON guardians (relationship);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_guardians_full_name_trgm
    ON guardians USING gin (lower(full_name) gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_guardians_email_trgm
    ON guardians USING gin (lower(coalesce(email, '')) gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_guardians_rut_trgm
    ON guardians USING gin (lower(coalesce(rut, '')) gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_parents_rut
    ON parents (rut)
    WHERE rut IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_supporters_rut
    ON supporters (rut)
    WHERE rut IS NOT NULL;
```

### 7. Indices para `documents`

Consultas cubiertas: documentos por postulacion, conteo total, conteo aprobados, orden por subida.

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_documents_application_upload
    ON documents (application_id, upload_date DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_documents_application_approval
    ON documents (application_id, approval_status);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_documents_approved_by
    ON documents (approved_by)
    WHERE approved_by IS NOT NULL;
```

### 8. Indices para `evaluations`

Consultas cubiertas: evaluaciones por postulacion/evaluador, pendientes, reportes por tipo, asignaciones, migraciones retroactivas y validacion de duplicados por tipo.

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_evaluations_application_recent
    ON evaluations (application_id, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_evaluations_evaluator_recent
    ON evaluations (evaluator_id, created_at DESC)
    WHERE evaluator_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_evaluations_evaluator_status_recent
    ON evaluations (evaluator_id, status, created_at DESC)
    WHERE evaluator_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_evaluations_status_recent
    ON evaluations (status, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_evaluations_type_recent
    ON evaluations (evaluation_type, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_evaluations_subject_recent
    ON evaluations (subject, created_at DESC)
    WHERE subject IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_evaluations_application_type
    ON evaluations (application_id, evaluation_type);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_evaluations_completed_score
    ON evaluations (status, score)
    WHERE score IS NOT NULL;
```

Recomendado si el negocio solo permite una evaluacion por postulacion y tipo:

```sql
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_evaluations_application_type
    ON evaluations (application_id, evaluation_type);
```

Antes de crear el indice unico, validar duplicados:

```sql
SELECT application_id, evaluation_type, count(*) AS total
FROM evaluations
GROUP BY application_id, evaluation_type
HAVING count(*) > 1
ORDER BY total DESC, application_id;
```

### 9. Indices para `interviews`

Consultas cubiertas: calendario, entrevistas por postulacion, disponibilidad por entrevistador/fecha, bloqueos, conteos por estado/tipo, confirmacion familiar.

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_interviews_application_scheduled
    ON interviews (application_id, scheduled_date DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_interviews_application_summary
    ON interviews (application_id)
    WHERE summary_sent = true;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_interviews_interviewer_date_status
    ON interviews (interviewer_id, scheduled_date, status, scheduled_time);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_interviews_second_interviewer_date_status
    ON interviews (second_interviewer_id, scheduled_date, status, scheduled_time)
    WHERE second_interviewer_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_interviews_scheduled_range
    ON interviews (scheduled_date, scheduled_time);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_interviews_status_date
    ON interviews (status, scheduled_date);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_interviews_type
    ON interviews (interview_type);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_interviews_created_desc
    ON interviews (created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_interviews_confirmation_status
    ON interviews (confirmation_status)
    WHERE confirmation_status IS NOT NULL;
```

### 10. Indices para `interviewer_schedules`

Consultas cubiertas: horarios por entrevistador/año, templates disponibles, duplicados, entrevistadores con horarios activos.

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_interviewer_schedules_interviewer_year_order
    ON interviewer_schedules (interviewer_id, year DESC, day_of_week, start_time);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_interviewer_schedules_available_recurring
    ON interviewer_schedules (interviewer_id, year, day_of_week, start_time, end_time)
    WHERE is_active = true AND specific_date IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_interviewer_schedules_available_specific
    ON interviewer_schedules (interviewer_id, year, specific_date, start_time, end_time)
    WHERE is_active = true AND specific_date IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_interviewer_schedules_active_interviewer
    ON interviewer_schedules (interviewer_id)
    WHERE is_active = true;

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_interviewer_schedules_specific_slot
    ON interviewer_schedules (interviewer_id, year, specific_date, start_time, end_time)
    WHERE specific_date IS NOT NULL;

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_interviewer_schedules_recurring_slot
    ON interviewer_schedules (interviewer_id, year, day_of_week, start_time, end_time)
    WHERE specific_date IS NULL;
```

Antes de crear los indices unicos, validar duplicados:

```sql
SELECT interviewer_id, year, specific_date, start_time, end_time, count(*) AS total
FROM interviewer_schedules
WHERE specific_date IS NOT NULL
GROUP BY interviewer_id, year, specific_date, start_time, end_time
HAVING count(*) > 1;

SELECT interviewer_id, year, day_of_week, start_time, end_time, count(*) AS total
FROM interviewer_schedules
WHERE specific_date IS NULL
GROUP BY interviewer_id, year, day_of_week, start_time, end_time
HAVING count(*) > 1;
```

### 11. Indices para `notifications`

Consultas cubiertas: busqueda con filtros opcionales por destinatario, canal, tipo y estado.

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_notifications_recipient
    ON notifications (recipient_type, recipient_id, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_notifications_status_created
    ON notifications (status, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_notifications_channel_created
    ON notifications (channel, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_notifications_type_created
    ON notifications (type, created_at DESC)
    WHERE type IS NOT NULL;
```

### 12. Indices para `payments` y `payment_events`

Consultas cubiertas: idempotencia, pago activo por postulacion, invoice webhook, customer por usuario, deduplicacion de eventos.

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_payments_application_status_created
    ON payments (application_id, status, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_payments_guardian_customer_created
    ON payments (guardian_user_id, created_at DESC)
    WHERE provider_customer_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_payments_provider_invoice_created
    ON payments (provider_invoice_id, created_at DESC)
    WHERE provider_invoice_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_payments_status_expires
    ON payments (status, expires_at)
    WHERE expires_at IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_payment_events_payment_created
    ON payment_events (payment_id, created_at DESC)
    WHERE payment_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_payment_events_created
    ON payment_events (created_at DESC);
```

### 13. Indices JSONB opcionales

Solo ejecutar si se consulta por claves dentro de JSONB. Hoy no se observa ese patron en repositorios, por eso no es prioridad.

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_users_preferences_json
    ON users USING gin (preferences_json jsonb_path_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_complementary_forms_form_data
    ON complementary_forms USING gin (form_data jsonb_path_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_evaluations_interview_data
    ON evaluations USING gin (interview_data jsonb_path_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_notifications_template_data
    ON notifications USING gin (template_data jsonb_path_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS gin_payment_events_payload
    ON payment_events USING gin (payload jsonb_path_ops);
```

### 14. Actualizar estadisticas

Ejecutar despues de crear indices:

```sql
ANALYZE users;
ANALYZE email_verification_codes;
ANALYZE active_sessions;
ANALYZE refresh_tokens;
ANALYZE revoked_jtis;
ANALYZE students;
ANALYZE applications;
ANALYZE guardians;
ANALYZE parents;
ANALYZE supporters;
ANALYZE documents;
ANALYZE evaluations;
ANALYZE interviews;
ANALYZE interviewer_schedules;
ANALYZE notifications;
ANALYZE payments;
ANALYZE payment_events;
ANALYZE complementary_forms;
```

## Comandos de medicion

### Top consultas por tiempo total

```sql
SELECT
    calls,
    round(total_exec_time::numeric, 2) AS total_ms,
    round(mean_exec_time::numeric, 2) AS mean_ms,
    rows,
    left(query, 220) AS query
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 25;
```

### Top consultas por I/O

```sql
SELECT
    calls,
    shared_blks_read,
    shared_blks_hit,
    round(100.0 * shared_blks_hit / nullif(shared_blks_hit + shared_blks_read, 0), 2) AS hit_pct,
    left(query, 220) AS query
FROM pg_stat_statements
ORDER BY shared_blks_read DESC
LIMIT 25;
```

### Indices no usados despues de algunos dias de trafico

```sql
SELECT
    schemaname,
    relname AS table_name,
    indexrelname AS index_name,
    idx_scan,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
FROM pg_stat_user_indexes
WHERE idx_scan = 0
ORDER BY pg_relation_size(indexrelid) DESC;
```

### Tablas que necesitan vacuum/analyze

```sql
SELECT
    relname,
    n_live_tup,
    n_dead_tup,
    last_vacuum,
    last_autovacuum,
    last_analyze,
    last_autoanalyze
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC
LIMIT 30;
```

### FKs sin indice

```sql
WITH fk AS (
    SELECT
        conrelid,
        conname,
        conkey
    FROM pg_constraint
    WHERE contype = 'f'
),
idx AS (
    SELECT
        indrelid,
        indkey
    FROM pg_index
)
SELECT
    conrelid::regclass AS table_name,
    conname AS fk_name,
    conkey AS fk_columns
FROM fk
WHERE NOT EXISTS (
    SELECT 1
    FROM idx
    WHERE idx.indrelid = fk.conrelid
      AND idx.indkey::int2[] @> fk.conkey
)
ORDER BY table_name::text, fk_name;
```

## EXPLAIN para consultas criticas

Cambiar parametros por valores reales.

### Listado de postulaciones activas

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT ae.*
FROM applications ae
WHERE ae.deleted_at IS NULL
  AND ae.is_archived = false
ORDER BY ae.submission_date DESC, ae.id DESC
LIMIT 15 OFFSET 0;
```

### Postulaciones por estado y curso

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT ae.*
FROM applications ae
JOIN students s ON s.id = ae.student_id
WHERE ae.deleted_at IS NULL
  AND ae.is_archived = false
  AND ae.status = 'PENDING'
  AND s.grade_applied = 'PREKINDER'
ORDER BY ae.submission_date DESC, ae.id DESC
LIMIT 15 OFFSET 0;
```

### Busqueda de estudiante

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM students s
WHERE lower(s.first_name) LIKE lower('%juan%')
   OR lower(coalesce(s.paternal_last_name, '')) LIKE lower('%juan%')
   OR lower(coalesce(s.maternal_last_name, '')) LIKE lower('%juan%')
   OR lower(coalesce(s.rut, '')) LIKE lower('%juan%')
LIMIT 50;
```

### Calendario de entrevistas

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM interviews
WHERE scheduled_date >= current_date
  AND scheduled_date <= current_date + interval '30 days'
ORDER BY scheduled_date ASC, scheduled_time ASC;
```

### Disponibilidad/bloqueo por entrevistador

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM interviews
WHERE interviewer_id = 123
  AND scheduled_date = current_date
  AND status IN ('SCHEDULED', 'CONFIRMED', 'PENDING');
```

### Evaluaciones pendientes de evaluador

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM evaluations
WHERE evaluator_id = 123
  AND status IN ('PENDING', 'IN_PROGRESS')
ORDER BY created_at DESC;
```

### Login por email

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM users
WHERE lower(email) = lower('usuario@colegio.cl');
```

## Reescrituras necesarias en codigo

Estas mejoras no se solucionan solo con indices.

1. `DashboardService` usa varias veces `applicationRepository.findAll()` y agrupa en Java. Reemplazar por queries agregadas:
   - `count(*) group by status`
   - `count(*) group by date_trunc('month', created_at)`
   - `count(*) group by students.grade_applied`
2. `DashboardService.documentReviewDashboard()` hace conteos por postulacion:
   - `documentRepository.countByApplicationId(...)`
   - `documentRepository.countByApplicationIdAndApprovalStatus(...)`
   - `interviewRepository.findByApplicationId...`
   Debe reemplazarse por una query con `LEFT JOIN` y agregados.
3. `UserService.detail()` y `delete()` cargan listas para contar. Cambiar a `countByEvaluatorId`, `countVisibleForInterviewer`, `countByInterviewerId`.
4. `InterviewRepository.findForCalendar()` filtra `REJECTED_BY_FAMILY` en Java. Crear query SQL/JPA con `status <> 'REJECTED_BY_FAMILY'`.
5. `NotificationRepository.search()` usa condiciones `(:param is null or col = :param)`. Para tablas grandes, separar metodos por combinacion de filtros principales o usar Criteria API para que PostgreSQL reciba predicados sargables.
6. Las busquedas `LIKE '%term%'` deberian exigir minimo 3 caracteres para aprovechar mejor `pg_trgm`; para 1-2 caracteres conviene no buscar o usar busqueda por prefijo.
7. Los endpoints que devuelven `findAll()` deben paginarse. `findAllByOrderByCreatedAtDesc()` en entrevistas/evaluaciones no escala.

## Configuracion recomendada para inserts/updates

Agregar en `application.yml` cuando se hagan inserts en lote o flujos con varios `save()`:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50
        order_inserts: true
        order_updates: true
        batch_versioned_data: true
```

Para jobs de limpieza, borrar por lotes con CTE para evitar transacciones enormes:

```sql
WITH doomed AS (
    SELECT id
    FROM refresh_tokens
    WHERE expires_at < now() - interval '7 days'
    ORDER BY expires_at
    LIMIT 5000
)
DELETE FROM refresh_tokens r
USING doomed d
WHERE r.id = d.id;
```

Para `revoked_jtis`:

```sql
WITH doomed AS (
    SELECT jti
    FROM revoked_jtis
    WHERE expires_at < now()
    ORDER BY expires_at
    LIMIT 5000
)
DELETE FROM revoked_jtis r
USING doomed d
WHERE r.jti = d.jti;
```

## Mantenimiento operativo

### Autovacuum mas agresivo en tablas con muchos updates/deletes

```sql
ALTER TABLE active_sessions SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_analyze_scale_factor = 0.01
);

ALTER TABLE refresh_tokens SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_analyze_scale_factor = 0.01
);

ALTER TABLE revoked_jtis SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_analyze_scale_factor = 0.01
);

ALTER TABLE notifications SET (
    autovacuum_vacuum_scale_factor = 0.05,
    autovacuum_analyze_scale_factor = 0.02
);

ALTER TABLE payment_events SET (
    autovacuum_vacuum_scale_factor = 0.05,
    autovacuum_analyze_scale_factor = 0.02
);
```

### Reindex concurrente si hay bloat severo

```sql
REINDEX INDEX CONCURRENTLY ix_applications_active_recent;
REINDEX INDEX CONCURRENTLY ix_interviews_scheduled_range;
REINDEX INDEX CONCURRENTLY ix_evaluations_status_recent;
```

## Prioridad exacta

1. Crear indices de FKs y listados principales: `applications`, `documents`, `evaluations`, `interviews`, `interviewer_schedules`.
2. Crear `pg_trgm` e indices GIN para busquedas en `students`, `guardians`, `users`.
3. Crear indices de autenticacion/tokens: `users.lower(email)`, `active_sessions.token_hash`, `refresh_tokens` activos.
4. Reescribir dashboards que usan `findAll()` y N+1.
5. Medir `pg_stat_statements` una semana y eliminar indices no usados.

## Criterio de exito

- Listados paginados principales bajo 100 ms con cache caliente.
- Login y validacion de sesion bajo 20 ms.
- Calendario mensual de entrevistas bajo 150 ms.
- Dashboards sin `findAll()` y sin N+1.
- `EXPLAIN` debe mostrar `Index Scan`, `Bitmap Index Scan` o `Bitmap Heap Scan` en consultas filtradas; evitar `Seq Scan` en tablas grandes salvo agregados globales justificados.
