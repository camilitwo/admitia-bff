-- Agrega columnas para guardar intención de inclusión y año de egreso del exalumno
-- declarados por el apoderado al momento de la postulación.
-- Nota: estas columnas pertenecen al modelo Prekinder (applications), no al modelo
-- general (students). No hay duplicación con StudentEntity porque Prekinder no lo usa.
ALTER TABLE applications
    ADD COLUMN is_inclusion_student BOOLEAN NOT NULL DEFAULT FALSE;

-- Año de egreso del familiar que fue alumno del colegio (prioridad: padre → madre).
ALTER TABLE applications
    ADD COLUMN is_alumni_parent_year INTEGER;
