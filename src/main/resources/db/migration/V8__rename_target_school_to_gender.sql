-- Rename target_school to gender and remap values
-- Old values: MONTE_TABOR (male), NAZARET (female), HIJO_FUNCIONARIO, NINGUNA
-- New values: MALE, FEMALE

ALTER TABLE students ADD COLUMN IF NOT EXISTS gender VARCHAR(20);

UPDATE students SET gender =
    CASE
        WHEN target_school = 'MONTE_TABOR' THEN 'MALE'
        WHEN target_school = 'NAZARET' THEN 'FEMALE'
        ELSE NULL
    END
WHERE target_school IN ('MONTE_TABOR', 'NAZARET');

ALTER TABLE students DROP COLUMN IF EXISTS target_school;