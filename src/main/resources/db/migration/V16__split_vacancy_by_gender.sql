-- Agregar columnas para vacantes separadas por género
ALTER TABLE grade_availability
ADD COLUMN has_vacancy_m BOOLEAN NOT NULL DEFAULT TRUE,
ADD COLUMN has_vacancy_f BOOLEAN NOT NULL DEFAULT TRUE;

-- Migrar datos: si tiene vacante, asignar a ambos géneros
UPDATE grade_availability SET has_vacancy_m = has_vacancy, has_vacancy_f = has_vacancy WHERE has_vacancy = true;

-- Eliminar columna vieja
ALTER TABLE grade_availability DROP COLUMN has_vacancy;
