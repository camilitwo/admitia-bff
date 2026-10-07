ALTER TABLE applications ADD COLUMN IF NOT EXISTS process_code VARCHAR(80);

UPDATE applications
   SET academic_year = 2027
 WHERE academic_year IS NULL
    OR academic_year = 2026;

UPDATE applications
   SET process_code = 'KIV-2027-01'
 WHERE process_code IS NULL
   AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_applications_process_active
    ON applications(process_code, academic_year, is_archived, deleted_at);

