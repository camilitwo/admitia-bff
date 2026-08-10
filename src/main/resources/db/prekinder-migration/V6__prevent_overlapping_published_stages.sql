-- Un proceso no puede exponer dos etapas publicadas al mismo tiempo.
-- El rango [apertura, cierre) permite que una etapa comience exactamente
-- cuando termina la anterior.
ALTER TABLE process_waves
    ADD CONSTRAINT ex_process_published_stage_window
    EXCLUDE USING gist (
        process_id WITH =,
        tstzrange(opens_at, closes_at, '[)') WITH &&
    )
    WHERE (status = 'PUBLISHED');
