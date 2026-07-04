-- Migración: Crear evaluaciones CYCLE_DIRECTOR_REPORT para entrevistas CYCLE_DIRECTOR existentes
-- que no tienen su correspondiente informe

INSERT INTO evaluations (application_id, evaluator_id, evaluation_type, status, score, max_score, created_at, updated_at)
SELECT
    i.application_id,
    i.interviewer_id,
    'CYCLE_DIRECTOR_REPORT',
    CASE
        WHEN i.status = 'COMPLETED' THEN 'IN_PROGRESS'
        ELSE 'PENDING'
    END,
    0,
    100,
    NOW(),
    NOW()
FROM interviews i
WHERE i.interview_type = 'CYCLE_DIRECTOR'
  AND i.application_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM evaluations e
      WHERE e.application_id = i.application_id
        AND e.evaluation_type = 'CYCLE_DIRECTOR_REPORT'
  );
