-- Las jornadas de evaluación ahora se crean y editan directamente desde Torre
-- de control (antes sólo se creaban implícitamente al agendar el primer grupo
-- del día), por lo que necesitan la misma trazabilidad de última modificación
-- que ya tienen salas y grupos.
ALTER TABLE evaluation_days
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
