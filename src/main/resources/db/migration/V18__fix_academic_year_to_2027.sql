-- =============================================================================
-- V18: Fix academic_year for 2027 admission cycle
--
-- Problem: Applications created via frontend are missing academic_year or have
-- wrong value (2026 instead of 2027) because the frontend was not sending it.
--
-- Solution: Update all applications to academic_year = 2027 where:
--   - academic_year IS NULL, OR
--   - academic_year = 2026
--
-- This script is IDEMPOTENT - safe to run multiple times.
-- =============================================================================

-- Preview: Show how many records will be affected
SELECT
    'Applications to update' AS description,
    COUNT(*) AS count
FROM applications
WHERE academic_year IS NULL OR academic_year = 2026;

-- Detail preview: Show breakdown by current academic_year value
SELECT
    COALESCE(academic_year::TEXT, 'NULL') AS current_academic_year,
    COUNT(*) AS count
FROM applications
WHERE academic_year IS NULL OR academic_year = 2026
GROUP BY academic_year;

-- =============================================================================
-- UPDATE STATEMENT (uncomment to execute)
-- =============================================================================
-- UPDATE applications
-- SET academic_year = 2027
-- WHERE academic_year IS NULL OR academic_year = 2026;
