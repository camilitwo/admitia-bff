WITH latest_cycle_interview AS (
    SELECT DISTINCT ON (i.application_id)
        i.application_id,
        CASE
            WHEN first_user.role = 'CYCLE_DIRECTOR' THEN i.interviewer_id
            WHEN second_user.role = 'CYCLE_DIRECTOR' THEN i.second_interviewer_id
            ELSE NULL
        END AS cycle_director_id,
        CASE
            WHEN first_user.role = 'PSYCHOLOGIST' THEN i.interviewer_id
            WHEN second_user.role = 'PSYCHOLOGIST' THEN i.second_interviewer_id
            ELSE NULL
        END AS psychologist_id
    FROM interviews i
    LEFT JOIN users first_user ON first_user.id = i.interviewer_id
    LEFT JOIN users second_user ON second_user.id = i.second_interviewer_id
    WHERE i.interview_type = 'CYCLE_DIRECTOR'
      AND i.status NOT IN ('CANCELLED', 'RESCHEDULED', 'REJECTED_BY_FAMILY')
    ORDER BY i.application_id, i.created_at DESC
)
UPDATE evaluations evaluation
SET evaluator_id = CASE
        WHEN evaluation.evaluation_type IN ('CYCLE_DIRECTOR_INTERVIEW', 'CYCLE_DIRECTOR_REPORT')
            THEN interview.cycle_director_id
        WHEN evaluation.evaluation_type = 'PSYCHOLOGICAL_INTERVIEW'
            THEN interview.psychologist_id
        ELSE evaluation.evaluator_id
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
      WHEN evaluation.evaluation_type IN ('CYCLE_DIRECTOR_INTERVIEW', 'CYCLE_DIRECTOR_REPORT')
          THEN interview.cycle_director_id
      WHEN evaluation.evaluation_type = 'PSYCHOLOGICAL_INTERVIEW'
          THEN interview.psychologist_id
      ELSE NULL
  END IS NOT NULL;
