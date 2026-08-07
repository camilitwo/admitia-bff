-- Permite que los usuarios autenticados se vinculen de forma idempotente con
-- su actor Prekínder. PostgreSQL admite múltiples NULL en una restricción
-- UNIQUE, por lo que profesionales aún no vinculados siguen siendo válidos.
--
-- V2 creó un índice único parcial. Ese índice protege los datos, pero no puede
-- inferirse desde un ON CONFLICT(external_subject) sin repetir su predicado.
ALTER TABLE actors
    ADD CONSTRAINT uq_actors_external_subject_key UNIQUE (external_subject);
