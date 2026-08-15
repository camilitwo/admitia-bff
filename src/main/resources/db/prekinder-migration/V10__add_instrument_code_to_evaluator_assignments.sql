-- Add instrument_code to group_evaluator_assignments for tracking which instrument
-- each evaluator is assigned to in a group (derived from their roleCode)

ALTER TABLE group_evaluator_assignments
ADD COLUMN instrument_code VARCHAR(64);

-- Backfill existing assignments with instrument based on evaluator's professional profile
UPDATE group_evaluator_assignments gea
SET instrument_code = pp.role_code
FROM professional_profiles pp
WHERE pp.professional_id = gea.evaluator_id
  AND pp.role_code LIKE 'PK_EVALUATOR_%';

-- Set default for any remaining NULL values
UPDATE group_evaluator_assignments
SET instrument_code = 'UNKNOWN'
WHERE instrument_code IS NULL;

-- Make NOT NULL after backfill
ALTER TABLE group_evaluator_assignments
ALTER COLUMN instrument_code SET NOT NULL;

-- Add comment for documentation
COMMENT ON COLUMN group_evaluator_assignments.instrument_code IS
  'Instrument code derived from evaluator role (e.g., PSYCHOLOGY, ACADEMIC, PSYCHOMOTOR)';
