-- El contenido identificador de una pauta pertenece a su versión. Así, una
-- versión publicada conserva nombre e instrumento aunque exista un borrador
-- posterior con cambios.
ALTER TABLE evaluation_template_versions
    ADD COLUMN name VARCHAR(160),
    ADD COLUMN instrument_code VARCHAR(64);

-- Este backfill es el único cambio autorizado sobre versiones ya publicadas.
-- El trigger se suspende dentro de la misma transacción de Flyway y se vuelve a
-- habilitar antes de imponer NOT NULL.
ALTER TABLE evaluation_template_versions
    DISABLE TRIGGER trg_published_rubric_version_immutable;

UPDATE evaluation_template_versions AS rubric_version
   SET name = rubric.name,
       instrument_code = rubric.type_code
  FROM evaluation_templates AS rubric
 WHERE rubric.evaluation_template_id = rubric_version.evaluation_template_id;

ALTER TABLE evaluation_template_versions
    ENABLE TRIGGER trg_published_rubric_version_immutable;

ALTER TABLE evaluation_template_versions
    ALTER COLUMN name SET NOT NULL,
    ALTER COLUMN instrument_code SET NOT NULL;

-- Actualiza la protección para que las nuevas columnas versionadas tampoco
-- puedan cambiar al reemplazar una versión publicada por su sucesora.
CREATE OR REPLACE FUNCTION prevent_published_rubric_version_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        IF TG_OP = 'UPDATE'
           AND NEW.status = 'SUPERSEDED'
           AND NEW.evaluation_template_id = OLD.evaluation_template_id
           AND NEW.version = OLD.version
           AND NEW.maximum_score IS NOT DISTINCT FROM OLD.maximum_score
           AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at
           AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at
           AND NEW.name = OLD.name
           AND NEW.instrument_code = OLD.instrument_code THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'PUBLISHED_RUBRIC_IMMUTABLE';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

-- El catálogo admite más de una pauta reutilizable para un mismo instrumento.
-- La restricción de una pauta por instrumento corresponde a la asociación del
-- proceso, no a las definiciones disponibles en el catálogo.
DROP INDEX IF EXISTS uq_global_active_rubric_code;

CREATE INDEX ix_active_rubric_instrument
    ON evaluation_templates(type_code)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_rubric_version_instrument
    ON evaluation_template_versions(instrument_code, status);
