-- PK-243: Agregar flag para no imponer edad máxima a estudiantes de inclusión
ALTER TABLE prekinder_process_configuration
    ADD COLUMN no_max_age_for_inclusion BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN prekinder_process_configuration.no_max_age_for_inclusion IS
    'Cuando es TRUE y el postulante es estudiante de inclusión, se omite la validación de edad máxima.';
