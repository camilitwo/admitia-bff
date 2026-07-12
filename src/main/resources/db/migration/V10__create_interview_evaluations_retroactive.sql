-- Crear evaluaciones retroactivamente para entrevistas que no tienen evaluación correspondiente
-- Mapping: FAMILY -> FAMILY_INTERVIEW, CYCLE_DIRECTOR -> CYCLE_DIRECTOR_INTERVIEW, PSYCHOLOGICAL -> PSYCHOLOGICAL_INTERVIEW

INSERT INTO evaluations (application_id, evaluator_id, evaluation_type, status, created_at, updated_at)
SELECT
    i.application_id,
    i.interviewer_id,
    CASE
        WHEN i.interview_type = 'FAMILY' THEN 'FAMILY_INTERVIEW'
        WHEN i.interview_type = 'CYCLE_DIRECTOR' THEN 'CYCLE_DIRECTOR_INTERVIEW'
        WHEN i.interview_type = 'PSYCHOLOGICAL' THEN 'PSYCHOLOGICAL_INTERVIEW'
    END as evaluation_type,
    'PENDING' as status,
    NOW() as created_at,
    NOW() as updated_at
FROM interviews i
WHERE i.interview_type IN ('FAMILY', 'CYCLE_DIRECTOR', 'PSYCHOLOGICAL')
AND i.application_id IS NOT NULL
AND NOT EXISTS (
    SELECT 1 FROM evaluations e
    WHERE e.application_id = i.application_id
    AND e.evaluation_type = CASE
        WHEN i.interview_type = 'FAMILY' THEN 'FAMILY_INTERVIEW'
        WHEN i.interview_type = 'CYCLE_DIRECTOR' THEN 'CYCLE_DIRECTOR_INTERVIEW'
        WHEN i.interview_type = 'PSYCHOLOGICAL' THEN 'PSYCHOLOGICAL_INTERVIEW'
    END
);

-- Actualizar evaluator_id para las entrevistas de directorm1 (id=60) y otros entrevistadores
UPDATE evaluations e
SET evaluator_id = i.interviewer_id
FROM interviews i
WHERE e.application_id = i.application_id
AND e.evaluation_type = CASE
    WHEN i.interview_type = 'FAMILY' THEN 'FAMILY_INTERVIEW'
    WHEN i.interview_type = 'CYCLE_DIRECTOR' THEN 'CYCLE_DIRECTOR_INTERVIEW'
    WHEN i.interview_type = 'PSYCHOLOGICAL' THEN 'PSYCHOLOGICAL_INTERVIEW'
END
AND e.evaluator_id IS NULL
AND i.interviewer_id IS NOT NULL;
