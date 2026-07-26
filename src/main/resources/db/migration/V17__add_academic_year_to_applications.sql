-- Agregar columna academic_year a la tabla de postulaciones
ALTER TABLE applications ADD COLUMN academic_year INTEGER;

-- Actualizar aplicaciones existentes con el año de su submission_date o created_at
UPDATE applications SET academic_year = EXTRACT(YEAR FROM COALESCE(submission_date, created_at));

-- Las nuevas aplicaciones se setearán en ApplicationService.create()
