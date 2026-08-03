package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.service.AdmissionResultEmailFactory.PreparedEmail;
import cl.mtn.admitiabff.service.notification.ResendEmailSender;
import cl.mtn.admitiabff.service.notification.ResendEmailSender.ResendDeliveryException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdmissionResultDispatchWorker {
    private static final Logger log = LoggerFactory.getLogger(AdmissionResultDispatchWorker.class);
    private static final Duration PROVIDER_IDEMPOTENCY_WINDOW = Duration.ofHours(23);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationRepository applicationRepository;
    private final AdmissionResultEmailFactory emailFactory;
    private final ResendEmailSender emailSender;
    private final boolean enabled;
    private final int batchSize;
    private final int leaseMinutes;
    private final int maxAttempts;

    public AdmissionResultDispatchWorker(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ApplicationRepository applicationRepository,
            AdmissionResultEmailFactory emailFactory,
            ResendEmailSender emailSender,
            @Value("${app.admission-cycle.dispatch-enabled:false}") boolean enabled,
            @Value("${app.admission-cycle.batch-size:50}") int batchSize,
            @Value("${app.admission-cycle.lease-minutes:10}") int leaseMinutes,
            @Value("${app.admission-cycle.max-attempts:5}") int maxAttempts) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.applicationRepository = applicationRepository;
        this.emailFactory = emailFactory;
        this.emailSender = emailSender;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
        this.leaseMinutes = Math.max(1, leaseMinutes);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${app.admission-cycle.worker-delay-ms:3000}")
    public void dispatchReadyResults() {
        if (!enabled || !emailSender.isConfigured()) return;
        try {
            recoverExpiredLeases();
            String leaseToken = UUID.randomUUID().toString();
            List<DispatchTask> tasks = transactionTemplate.execute(status -> claimBatch(leaseToken));
            if (tasks == null || tasks.isEmpty()) {
                finalizeCompletedCycles();
                return;
            }
            processBatch(tasks);
            finalizeCompletedCycles();
        } catch (Exception ex) {
            log.error("[admission-dispatch] Falló un ciclo del trabajador: {}", ex.getMessage(), ex);
        }
    }

    private List<DispatchTask> claimBatch(String leaseToken) {
        return jdbcTemplate.query("""
                WITH candidates AS (
                    SELECT d.id
                      FROM admission_result_dispatches d
                      JOIN admission_cycles c ON c.id = d.cycle_id
                     WHERE c.status = 'PUBLISHING'
                       AND d.status = 'PENDING'
                       AND d.next_attempt_at <= CURRENT_TIMESTAMP
                     ORDER BY d.id
                     FOR UPDATE OF d SKIP LOCKED
                     LIMIT ?
                )
                UPDATE admission_result_dispatches d
                   SET status = 'PROCESSING', lease_token = ?, locked_at = CURRENT_TIMESTAMP,
                       attempts = d.attempts + 1,
                       first_attempt_at = COALESCE(d.first_attempt_at, CURRENT_TIMESTAMP),
                       updated_at = CURRENT_TIMESTAMP
                  FROM candidates
                 WHERE d.id = candidates.id
                RETURNING d.id, d.cycle_id, d.application_id, d.recipient_email,
                          d.recipient_name, d.attempts, d.first_attempt_at,
                          d.subject, d.body, d.idempotency_key, d.lease_token
                """, (rs, rowNum) -> new DispatchTask(
                    rs.getLong("id"),
                    rs.getLong("cycle_id"),
                    rs.getLong("application_id"),
                    rs.getString("recipient_email"),
                    rs.getString("recipient_name"),
                    rs.getInt("attempts"),
                    rs.getTimestamp("first_attempt_at").toLocalDateTime(),
                    rs.getString("subject"),
                    rs.getString("body"),
                    rs.getString("idempotency_key"),
                    rs.getString("lease_token")), batchSize, leaseToken);
    }

    private void processBatch(List<DispatchTask> tasks) {
        List<Long> applicationIds = tasks.stream().map(DispatchTask::applicationId).distinct().toList();
        Map<Long, ApplicationEntity> applications = applicationRepository
                .findAllForAdmissionDispatch(applicationIds)
                .stream()
                .collect(Collectors.toMap(ApplicationEntity::getId, application -> application));

        Map<Long, PreparedEmail> prepared = new HashMap<>();
        List<DispatchTask> renderFailures = new ArrayList<>();
        for (DispatchTask task : tasks) {
            if (task.subject() != null && task.body() != null) continue;
            ApplicationEntity application = applications.get(task.applicationId());
            if (application == null) {
                renderFailures.add(task);
                continue;
            }
            try {
                prepared.put(task.id(), emailFactory.prepare(application));
            } catch (Exception ex) {
                log.warn("[admission-dispatch] No se pudo preparar applicationId={}: {}",
                        task.applicationId(), ex.getMessage());
                renderFailures.add(task);
            }
        }
        freezeRenderedPayloads(prepared, tasks);

        List<DispatchOutcome> outcomes = new ArrayList<>(tasks.size());
        LocalDateTime now = LocalDateTime.now();
        for (DispatchTask task : tasks) {
            if (renderFailures.contains(task)) {
                outcomes.add(DispatchOutcome.failed(task, "No fue posible construir el correo"));
                continue;
            }
            PreparedEmail rendered = prepared.get(task.id());
            String subject = task.subject() != null ? task.subject() : rendered.subject();
            String body = task.body() != null ? task.body() : rendered.html();
            try {
                String providerId = emailSender.send(
                        task.recipientEmail(), subject, body, task.idempotencyKey());
                outcomes.add(DispatchOutcome.sent(task, providerId));
            } catch (ResendDeliveryException ex) {
                outcomes.add(failureOutcome(task, ex, now));
            } catch (Exception ex) {
                outcomes.add(task.attempts() >= maxAttempts
                        ? DispatchOutcome.failed(task, "Error inesperado durante el envío")
                        : DispatchOutcome.pending(task, nextAttempt(task.attempts()), "Error temporal durante el envío"));
            }
        }
        persistOutcomes(outcomes);
        log.info("[admission-dispatch] Lote procesado size={} sent={} retry={} failed={}",
                outcomes.size(),
                outcomes.stream().filter(item -> "SENT".equals(item.status())).count(),
                outcomes.stream().filter(item -> "PENDING".equals(item.status())).count(),
                outcomes.stream().filter(item -> "FAILED".equals(item.status()) || "UNKNOWN".equals(item.status())).count());
    }

    private DispatchOutcome failureOutcome(DispatchTask task, ResendDeliveryException ex, LocalDateTime now) {
        boolean outsideSafeWindow = ex.isDeliveryUnknown()
                && Duration.between(task.firstAttemptAt(), now).compareTo(PROVIDER_IDEMPOTENCY_WINDOW) >= 0;
        if (outsideSafeWindow) {
            return DispatchOutcome.unknown(task, "Entrega no confirmada fuera de la ventana idempotente");
        }
        if (ex.isRetryable() && task.attempts() < maxAttempts) {
            return DispatchOutcome.pending(task, nextAttempt(task.attempts()), safeError(ex));
        }
        if (ex.isDeliveryUnknown()) {
            return DispatchOutcome.unknown(task, "Entrega no confirmada; requiere revisión manual");
        }
        return DispatchOutcome.failed(task, safeError(ex));
    }

    private LocalDateTime nextAttempt(int attempts) {
        long minutes = Math.min(60, 1L << Math.min(Math.max(attempts - 1, 0), 6));
        return LocalDateTime.now().plusMinutes(minutes);
    }

    private void freezeRenderedPayloads(Map<Long, PreparedEmail> prepared, List<DispatchTask> tasks) {
        if (prepared.isEmpty()) return;
        List<Map.Entry<Long, PreparedEmail>> entries = new ArrayList<>(prepared.entrySet());
        Map<Long, String> leases = tasks.stream().collect(Collectors.toMap(DispatchTask::id, DispatchTask::leaseToken));
        jdbcTemplate.batchUpdate("""
                UPDATE admission_result_dispatches
                   SET subject = ?, body = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND lease_token = ? AND subject IS NULL AND body IS NULL
                """, entries, entries.size(), (PreparedStatement ps, Map.Entry<Long, PreparedEmail> entry) -> {
                    ps.setString(1, entry.getValue().subject());
                    ps.setString(2, entry.getValue().html());
                    ps.setLong(3, entry.getKey());
                    ps.setString(4, leases.get(entry.getKey()));
                });
    }

    private void persistOutcomes(List<DispatchOutcome> outcomes) {
        if (outcomes.isEmpty()) return;
        jdbcTemplate.batchUpdate("""
                UPDATE admission_result_dispatches
                   SET status = ?, next_attempt_at = ?, locked_at = NULL, lease_token = NULL,
                       provider_message_id = ?, last_error = ?, sent_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND lease_token = ? AND status = 'PROCESSING'
                """, outcomes, outcomes.size(), (PreparedStatement ps, DispatchOutcome outcome) -> {
                    ps.setString(1, outcome.status());
                    ps.setTimestamp(2, Timestamp.valueOf(outcome.nextAttemptAt()));
                    ps.setString(3, outcome.providerMessageId());
                    ps.setString(4, outcome.error());
                    if (outcome.sentAt() == null) ps.setTimestamp(5, null);
                    else ps.setTimestamp(5, Timestamp.valueOf(outcome.sentAt()));
                    ps.setLong(6, outcome.taskId());
                    ps.setString(7, outcome.leaseToken());
                });
    }

    private void recoverExpiredLeases() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(leaseMinutes);
        LocalDateTime safeWindow = LocalDateTime.now().minus(PROVIDER_IDEMPOTENCY_WINDOW);
        jdbcTemplate.update("""
                UPDATE admission_result_dispatches
                   SET status = CASE WHEN first_attempt_at < ? THEN 'UNKNOWN' ELSE 'PENDING' END,
                       next_attempt_at = CURRENT_TIMESTAMP, locked_at = NULL, lease_token = NULL,
                       last_error = CASE WHEN first_attempt_at < ?
                           THEN 'Entrega no confirmada fuera de la ventana idempotente'
                           ELSE 'Lease vencido; envío reanudado de forma segura' END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE status = 'PROCESSING' AND locked_at < ?
                """, safeWindow, safeWindow, cutoff);
    }

    private void finalizeCompletedCycles() {
        jdbcTemplate.update("""
                WITH counts AS (
                    SELECT cycle_id,
                           count(*) AS total,
                           count(*) FILTER (WHERE status = 'SENT') AS sent,
                           count(*) FILTER (WHERE status IN ('FAILED', 'UNKNOWN')) AS failed,
                           count(*) FILTER (WHERE status IN ('PENDING', 'PROCESSING')) AS active
                      FROM admission_result_dispatches
                     GROUP BY cycle_id
                )
                UPDATE admission_cycles c
                   SET queued_count = counts.total,
                       sent_count = counts.sent,
                       failed_count = counts.failed,
                       status = CASE
                           WHEN counts.active = 0 AND counts.failed = 0 THEN 'CLOSED'
                           WHEN counts.active = 0 AND counts.failed > 0 THEN 'CLOSED_WITH_ERRORS'
                           ELSE c.status END,
                       closed_at = CASE WHEN counts.active = 0 THEN COALESCE(c.closed_at, CURRENT_TIMESTAMP) ELSE NULL END,
                       updated_at = CURRENT_TIMESTAMP,
                       version = c.version + 1
                  FROM counts
                 WHERE c.id = counts.cycle_id AND c.status = 'PUBLISHING'
                """);
    }

    private static String safeError(Exception ex) {
        String message = ex.getMessage() == null ? "Error de proveedor" : ex.getMessage();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private record DispatchTask(long id, long cycleId, long applicationId, String recipientEmail,
                                String recipientName, int attempts, LocalDateTime firstAttemptAt,
                                String subject, String body, String idempotencyKey, String leaseToken) {}

    private record DispatchOutcome(long taskId, String leaseToken, String status, LocalDateTime nextAttemptAt,
                                   String providerMessageId, String error, LocalDateTime sentAt) {
        static DispatchOutcome sent(DispatchTask task, String providerId) {
            LocalDateTime now = LocalDateTime.now();
            return new DispatchOutcome(task.id(), task.leaseToken(), "SENT", now, providerId, null, now);
        }
        static DispatchOutcome pending(DispatchTask task, LocalDateTime next, String error) {
            return new DispatchOutcome(task.id(), task.leaseToken(), "PENDING", next, null, error, null);
        }
        static DispatchOutcome failed(DispatchTask task, String error) {
            return new DispatchOutcome(task.id(), task.leaseToken(), "FAILED", LocalDateTime.now(), null, error, null);
        }
        static DispatchOutcome unknown(DispatchTask task, String error) {
            return new DispatchOutcome(task.id(), task.leaseToken(), "UNKNOWN", LocalDateTime.now(), null, error, null);
        }
    }

}
