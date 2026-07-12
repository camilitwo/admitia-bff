ALTER TABLE interviewer_schedules
    ALTER COLUMN day_of_week TYPE VARCHAR(20)
    USING CASE day_of_week::text
        WHEN '1' THEN 'MONDAY'
        WHEN '2' THEN 'TUESDAY'
        WHEN '3' THEN 'WEDNESDAY'
        WHEN '4' THEN 'THURSDAY'
        WHEN '5' THEN 'FRIDAY'
        WHEN '6' THEN 'SATURDAY'
        WHEN '7' THEN 'SUNDAY'
        ELSE day_of_week::text
    END;
