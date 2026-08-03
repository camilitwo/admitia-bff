-- SOLO LECTURA. No modifica datos.
-- Ejecutar antes de desplegar o habilitar el cierre maestro.

SELECT academic_year, status, count(*) AS applications
FROM applications
WHERE deleted_at IS NULL AND is_archived = false
GROUP BY academic_year, status
ORDER BY academic_year NULLS FIRST, status;

SELECT count(*) AS active_applications_without_academic_year
FROM applications
WHERE deleted_at IS NULL
  AND is_archived = false
  AND academic_year IS NULL;

SELECT
    count(*) AS applications_2027,
    count(*) FILTER (WHERE status NOT IN ('APPROVED', 'WAITLIST', 'REJECTED')) AS pending_decisions,
    count(*) FILTER (WHERE guardian_id IS NULL) AS missing_guardian,
    count(*) FILTER (
        WHERE guardian_id IS NOT NULL
          AND (g.email IS NULL OR trim(g.email) !~* '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$')
    ) AS invalid_guardian_email
FROM applications a
LEFT JOIN guardians g ON g.id = a.guardian_id
WHERE a.deleted_at IS NULL
  AND a.is_archived = false
  AND a.academic_year = 2027;

SELECT a.id, a.academic_year, a.status,
       CASE WHEN a.guardian_id IS NULL THEN 'SIN_APODERADO'
            WHEN g.email IS NULL OR trim(g.email) !~* '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
                THEN 'EMAIL_INVALIDO'
            WHEN a.status NOT IN ('APPROVED', 'WAITLIST', 'REJECTED') THEN 'SIN_DECISION_FINAL'
            ELSE 'OK' END AS validation
FROM applications a
LEFT JOIN guardians g ON g.id = a.guardian_id
WHERE a.deleted_at IS NULL
  AND a.is_archived = false
  AND (a.academic_year = 2027 OR a.academic_year IS NULL)
  AND (
      a.guardian_id IS NULL
      OR g.email IS NULL
      OR trim(g.email) !~* '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
      OR a.status NOT IN ('APPROVED', 'WAITLIST', 'REJECTED')
      OR a.academic_year IS NULL
  )
ORDER BY a.id;
