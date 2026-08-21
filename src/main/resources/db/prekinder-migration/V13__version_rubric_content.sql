-- El contenido identificador de una pauta pertenece a su versión. Así, una
-- versión publicada conserva nombre e instrumento aunque exista un borrador
-- posterior con cambios.
ALTER TABLE evaluation_template_versions
    ADD COLUMN name VARCHAR(160),
    ADD COLUMN instrument_code VARCHAR(64);

UPDATE evaluation_template_versions version
   SET name = template.name,
       instrument_code = template.type_code
  FROM evaluation_templates template
 WHERE template.evaluation_template_id = version.evaluation_template_id;

ALTER TABLE evaluation_template_versions
    ALTER COLUMN name SET NOT NULL,
    ALTER COLUMN instrument_code SET NOT NULL;

-- El catálogo admite más de una pauta reutilizable para un mismo instrumento.
-- La restricción de una pauta por instrumento corresponde a la asociación del
-- proceso, no a las definiciones disponibles en el catálogo.
DROP INDEX IF EXISTS uq_global_active_rubric_code;

CREATE INDEX ix_active_rubric_instrument
    ON evaluation_templates(type_code)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_rubric_version_instrument
    ON evaluation_template_versions(instrument_code, status);
