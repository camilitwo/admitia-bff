BEGIN;

-- Ejecutar sólo después de validar la limpieza y guardar un respaldo externo si
-- se necesita conservar la posibilidad de restaurar los datos de prueba.
CREATE TEMP TABLE admitia_drop_snapshot_parameters (
    confirmation text NOT NULL
) ON COMMIT DROP;

INSERT INTO admitia_drop_snapshot_parameters (confirmation)
VALUES (
    'NO'  -- Cambiar exactamente a: DROP_PRELAUNCH_BACKUP
);

DO $drop_snapshot$
DECLARE
    confirmation_value text;
BEGIN
    SELECT confirmation
      INTO confirmation_value
      FROM admitia_drop_snapshot_parameters;

    IF confirmation_value <> 'DROP_PRELAUNCH_BACKUP' THEN
        RAISE EXCEPTION 'Confirmación inválida. El snapshot no fue eliminado.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_namespace WHERE nspname = 'prelaunch_backup'
    ) THEN
        RAISE EXCEPTION 'El esquema prelaunch_backup no existe.';
    END IF;

    DROP SCHEMA prelaunch_backup CASCADE;
END
$drop_snapshot$;

COMMIT;

SELECT 'Snapshot prelaunch_backup eliminado definitivamente.' AS result;
