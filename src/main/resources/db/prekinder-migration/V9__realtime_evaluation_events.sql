-- Secuencia global para eventos operativos de la jornada. Los eventos WebSocket
-- sólo contienen identificadores y obligan al cliente a releer el snapshot
-- autorizado por HTTP; no transportan respuestas ni notas sensibles.
CREATE SEQUENCE prekinder_realtime_event_sequence START WITH 1 INCREMENT BY 1;

CREATE INDEX idx_outbox_evaluator_relay
    ON outbox_events(created_at)
    WHERE published_at IS NULL AND aggregate_type = 'EVALUATOR_WORKSPACE';
