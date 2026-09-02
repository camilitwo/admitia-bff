WITH latest_cycle_interview AS (
    SELECT DISTINCT ON (application_id)
        application_id,
        interviewer_id,
        second_interviewer_id
    FROM interviews
    WHERE interview_type = 'CYCLE_DIRECTOR'
      AND status NOT IN ('CANCELLED', 'RESCHEDULED', 'REJECTED_BY_FAMILY')
    ORDER BY application_id, created_at DESC
)
INSERT INTO evaluations (
    application_id,
    evaluator_id,
    evaluation_type,
    status,
    created_at,
    updated_at
)
SELECT
    interview.application_id,
    interview.second_interviewer_id,
    'PSYCHOLOGICAL_INTERVIEW',
    'PENDING',
    NOW(),
    NOW()
FROM latest_cycle_interview interview
WHERE interview.second_interviewer_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM evaluations evaluation
      WHERE evaluation.application_id = interview.application_id
        AND evaluation.evaluation_type = 'PSYCHOLOGICAL_INTERVIEW'
  );

WITH latest_cycle_interview AS (
    SELECT DISTINCT ON (application_id)
        application_id,
        interviewer_id,
        second_interviewer_id
    FROM interviews
    WHERE interview_type = 'CYCLE_DIRECTOR'
      AND status NOT IN ('CANCELLED', 'RESCHEDULED', 'REJECTED_BY_FAMILY')
    ORDER BY application_id, created_at DESC
)
UPDATE evaluations evaluation
SET evaluator_id = CASE
        WHEN evaluation.evaluation_type = 'PSYCHOLOGICAL_INTERVIEW'
            THEN interview.second_interviewer_id
        ELSE interview.interviewer_id
    END,
    updated_at = NOW()
FROM latest_cycle_interview interview
WHERE evaluation.application_id = interview.application_id
  AND evaluation.evaluation_type IN (
      'CYCLE_DIRECTOR_INTERVIEW',
      'CYCLE_DIRECTOR_REPORT',
      'PSYCHOLOGICAL_INTERVIEW'
  )
  AND evaluation.status IN ('PENDING', 'IN_PROGRESS')
  AND CASE
      WHEN evaluation.evaluation_type = 'PSYCHOLOGICAL_INTERVIEW'
          THEN interview.second_interviewer_id
      ELSE interview.interviewer_id
  END IS NOT NULL;
