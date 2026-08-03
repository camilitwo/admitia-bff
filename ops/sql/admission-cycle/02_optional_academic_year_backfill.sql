-- OPCIONAL Y MANUAL. El ROLLBACK final es intencional.
-- Este script NO actualiza por rango: exige una lista explícita de IDs revisados.
-- 1) Reemplace el 0 por los IDs aprobados (ejemplo: VALUES (42), (57)).
-- 2) Ejecute y revise el preview y los conteos.
-- 3) Sólo entonces cambie el ROLLBACK final por COMMIT y vuelva a ejecutar.

BEGIN;

CREATE TEMP TABLE approved_application_year_backfill_ids (
    application_id BIGINT PRIMARY KEY
);

-- Marcador deliberadamente inválido: reemplazar antes de cualquier ejecución real.
INSERT INTO approved_application_year_backfill_ids (application_id) VALUES (0);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM approved_application_year_backfill_ids WHERE application_id <= 0) THEN
        RAISE EXCEPTION 'Reemplace el ID marcador 0 por la lista explícita de postulaciones revisadas';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM approved_application_year_backfill_ids requested
        LEFT JOIN applications a ON a.id = requested.application_id
        WHERE a.id IS NULL
           OR a.deleted_at IS NOT NULL
           OR a.is_archived = true
           OR (a.academic_year IS NOT NULL AND a.academic_year <> 2026)
    ) THEN
        RAISE EXCEPTION 'La lista contiene IDs inexistentes, archivados, eliminados o con un año no permitido';
    END IF;
END $$;

SELECT a.id, a.academic_year, a.status, a.submission_date, a.created_at
FROM applications a
JOIN approved_application_year_backfill_ids requested ON requested.application_id = a.id
ORDER BY a.id
FOR UPDATE OF a;

UPDATE applications a
SET academic_year = 2027,
    updated_at = CURRENT_TIMESTAMP
FROM approved_application_year_backfill_ids requested
WHERE requested.application_id = a.id
  AND a.deleted_at IS NULL
  AND a.is_archived = false
  AND (a.academic_year IS NULL OR a.academic_year = 2026);

SELECT academic_year, count(*) AS applications_after_update
FROM applications
WHERE deleted_at IS NULL AND is_archived = false
GROUP BY academic_year
ORDER BY academic_year;

-- Cambiar por COMMIT sólo después de revisar los resultados anteriores.
ROLLBACK;
