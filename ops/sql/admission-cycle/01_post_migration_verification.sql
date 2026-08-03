-- SOLO LECTURA. Verifica que la migración aditiva esté instalada correctamente.

SELECT academic_year, status, total_applications, queued_count, sent_count, failed_count
FROM admission_cycles
ORDER BY academic_year DESC;

SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = current_schema()
  AND tablename IN ('admission_cycles', 'admission_result_dispatches')
ORDER BY tablename, indexname;

SELECT conname, contype, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid IN (
    'admission_cycles'::regclass,
    'admission_result_dispatches'::regclass
)
ORDER BY conrelid::regclass::text, conname;

SELECT count(*) AS dispatch_rows_before_first_close
FROM admission_result_dispatches;
