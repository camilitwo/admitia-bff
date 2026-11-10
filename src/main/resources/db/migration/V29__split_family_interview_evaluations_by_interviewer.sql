-- Preserve the existing family evaluation as the first interviewer's rubric.
UPDATE evaluations evaluation
SET evaluator_id = interview.interviewer_id,
    updated_at = NOW()
FROM interviews interview
WHERE evaluation.application_id = interview.application_id
  AND evaluation.evaluation_type = 'FAMILY_INTERVIEW'
  AND evaluation.evaluator_id IS NULL
  AND interview.interview_type = 'FAMILY'
  AND interview.status NOT IN ('CANCELLED', 'RESCHEDULED', 'REJECTED_BY_FAMILY')
  AND interview.interviewer_id IS NOT NULL;

-- Create the missing independent rubric for the second family interviewer.
INSERT INTO evaluations (
    application_id,
    evaluator_id,
    evaluation_type,
    status,
    evaluation_date,
    created_at,
    updated_at
)
SELECT
    interview.application_id,
    interview.second_interviewer_id,
    'FAMILY_INTERVIEW',
    'PENDING',
    CASE
        WHEN interview.scheduled_date IS NOT NULL AND interview.scheduled_time IS NOT NULL
            THEN interview.scheduled_date + interview.scheduled_time
        ELSE NULL
    END,
    NOW(),
    NOW()
FROM interviews interview
WHERE interview.interview_type = 'FAMILY'
  AND interview.status NOT IN ('CANCELLED', 'RESCHEDULED', 'REJECTED_BY_FAMILY')
  AND interview.application_id IS NOT NULL
  AND interview.second_interviewer_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM evaluations evaluation
      WHERE evaluation.application_id = interview.application_id
        AND evaluation.evaluation_type = 'FAMILY_INTERVIEW'
        AND evaluation.evaluator_id = interview.second_interviewer_id
  );

-- Prevent a retry or concurrent scheduling flow from duplicating one person's rubric.
CREATE UNIQUE INDEX IF NOT EXISTS uq_family_evaluation_application_evaluator
    ON evaluations(application_id, evaluator_id)
    WHERE evaluation_type = 'FAMILY_INTERVIEW' AND evaluator_id IS NOT NULL;
