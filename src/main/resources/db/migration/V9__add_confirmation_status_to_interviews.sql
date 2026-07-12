-- Agregar columna confirmation_status para rastrear respuesta de apoderados
ALTER TABLE interviews ADD COLUMN IF NOT EXISTS confirmation_status VARCHAR(50);

-- Comentario explicativo
COMMENT ON COLUMN interviews.confirmation_status IS 'Estado de confirmación por parte del apoderado: CONFIRMED o REJECTED_BY_FAMILY';
