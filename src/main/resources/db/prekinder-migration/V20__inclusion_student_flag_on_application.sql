-- Agrega columna para guardar la intención de inclusión declarada por el apoderado
-- en el momento de la postulación. Esto permite al dashboard saber si debe
-- mostrar el formulario de inclusión sin depender del proceso de configuración.
ALTER TABLE applications
    ADD COLUMN inclusion_student BOOLEAN NOT NULL DEFAULT FALSE;

-- Agrega columna para guardar el año de egreso del exalumno (familiar que fue alumno del colegio).
-- Se extrae de EligibilityDeclaration.fatherAlumni.graduationYear al momento de la postulación.
-- Si vienen ambos (padre y madre exalumnos), se guarda el del padre.
ALTER TABLE applications
    ADD COLUMN alumni_parent_year INTEGER;
