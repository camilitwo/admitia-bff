-- Clean up old 30-minute interviewer schedule slots
-- Keep only slots with 60-minute intervals or other durations
DELETE FROM interviewer_schedules
WHERE EXTRACT(MINUTE FROM (end_time - start_time)) = 30;

-- Log the cleanup
SELECT COUNT(*) as deleted_30min_slots FROM (
    SELECT 1 FROM interviewer_schedules
    WHERE EXTRACT(MINUTE FROM (end_time - start_time)) = 30
) t;
