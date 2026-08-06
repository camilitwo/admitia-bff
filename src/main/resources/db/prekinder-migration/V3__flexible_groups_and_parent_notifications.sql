-- Ajustes posteriores al flujo integral: los tamaños 3/9 pasan a ser valores sugeridos
-- y las notificaciones de agenda incluyen un payload mínimo sin datos sensibles.

ALTER TABLE evaluation_groups
    ADD COLUMN admin_capacity_override INTEGER CHECK (admin_capacity_override BETWEEN 1 AND 30),
    ADD COLUMN admin_evaluator_override INTEGER CHECK (admin_evaluator_override BETWEEN 1 AND 12);

ALTER TABLE notification_intents
    ADD COLUMN payload JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX idx_schedule_notifications_pending
    ON notification_intents(created_at)
    WHERE batch_id IS NULL AND status IN ('PENDING','FAILED');
