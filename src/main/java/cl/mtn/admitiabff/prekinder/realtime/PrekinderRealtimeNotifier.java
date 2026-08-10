package cl.mtn.admitiabff.prekinder.realtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderRealtimeNotifier {
    private static final String AGGREGATE_TYPE = "EVALUATOR_WORKSPACE";
    private final NamedParameterJdbcTemplate jdbc;
    private final PrekinderEventFanout fanout;
    private final TransactionTemplate isolatedTransaction;

    public PrekinderRealtimeNotifier(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
                                     @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
                                     PrekinderEventFanout fanout) {
        this.jdbc = jdbc;
        this.fanout = fanout;
        this.isolatedTransaction = new TransactionTemplate(manager);
        this.isolatedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void notifyAfterCommit(UUID actorId, String eventType) {
        notifyEntityAfterCommit(actorId, eventType);
    }

    public void notifyAfterCommit(UUID actorId, UUID processId, String eventType) {
        notifyEntityAfterCommit(actorId, eventType);
        if (processId != null && !processId.equals(actorId)) notifyEntityAfterCommit(processId, eventType);
    }

    private void notifyEntityAfterCommit(UUID actorId, String eventType) {
        if (actorId == null) return;
        UUID eventId = UUID.randomUUID();
        Long sequence = jdbc.queryForObject(
            "SELECT nextval('prekinder_realtime_event_sequence')", Map.of(), Long.class);
        long effectiveSequence = sequence == null ? 0 : sequence;
        jdbc.update("""
            INSERT INTO outbox_events(event_id, aggregate_type, aggregate_id, sequence, event_type)
            VALUES (:eventId, :aggregateType, :actorId, :sequence, :eventType)
            """, Map.of("eventId", eventId, "aggregateType", AGGREGATE_TYPE, "actorId", actorId,
            "sequence", effectiveSequence, "eventType", eventType));
        Runnable publish = () -> publish(new PendingEvent(eventId, actorId, effectiveSequence, eventType));
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
    }

    @Scheduled(fixedDelayString = "${app.prekinder.realtime.relay-delay-ms:2000}")
    public void relayPending() {
        List<PendingEvent> pending = jdbc.query("""
            SELECT event_id, aggregate_id, sequence, event_type
              FROM outbox_events
             WHERE aggregate_type = :aggregateType AND published_at IS NULL AND attempts < 20
             ORDER BY created_at
             LIMIT 100
            """, Map.of("aggregateType", AGGREGATE_TYPE), (rs, row) -> new PendingEvent(
            rs.getObject("event_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
            rs.getLong("sequence"), rs.getString("event_type")));
        pending.forEach(this::publish);
    }

    private void publish(PendingEvent event) {
        try {
            fanout.publish(new PrekinderEventFanout.MinimalEvent(
                event.eventId(), event.actorId(), event.sequence(), event.eventType()));
            isolatedTransaction.executeWithoutResult(status -> jdbc.update("""
                    UPDATE outbox_events SET published_at = now(), attempts = attempts + 1
                     WHERE event_id = :eventId AND published_at IS NULL
                    """, Map.of("eventId", event.eventId())));
        } catch (RuntimeException exception) {
            isolatedTransaction.executeWithoutResult(status -> jdbc.update(
                "UPDATE outbox_events SET attempts = attempts + 1 WHERE event_id = :eventId",
                Map.of("eventId", event.eventId())));
        }
    }

    private record PendingEvent(UUID eventId, UUID actorId, long sequence, String eventType) {}
}
