-- Agrupaciones de grupos: permiten operar varios evaluation_groups como una unidad
-- (reprogramar/confirmar en lote) sin alterar el modelo canónico de grupos.
-- Migración aditiva; no modifica evaluation_groups ni tablas relacionadas.

CREATE TABLE evaluation_group_clusters (
    cluster_id UUID PRIMARY KEY,
    process_id UUID NOT NULL REFERENCES admission_processes(process_id),
    evaluation_day_id UUID NOT NULL REFERENCES evaluation_days(day_id),
    name VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL REFERENCES actors(actor_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_active_cluster_name
    ON evaluation_group_clusters(process_id, evaluation_day_id, lower(name))
    WHERE status = 'ACTIVE';

CREATE TABLE evaluation_group_cluster_members (
    cluster_id UUID NOT NULL REFERENCES evaluation_group_clusters(cluster_id),
    group_id UUID NOT NULL REFERENCES evaluation_groups(group_id),
    position INTEGER NOT NULL DEFAULT 0,
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (cluster_id, group_id)
);

CREATE INDEX idx_cluster_members_group ON evaluation_group_cluster_members(group_id);
