package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderPublicationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final EnvelopeEncryptionService encryption;
    private final ObjectMapper mapper;

    public PrekinderPublicationService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
        PrekinderAccessService access, EnvelopeEncryptionService encryption, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.encryption = encryption;
        this.mapper = mapper;
    }

    public PreviewView preview(UUID processId) {
        PrekinderActor actor = access.requireAdmin();
        PreviewCalculation calculation = calculate(processId);
        UUID previewId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
        jdbc.update("""
            INSERT INTO publication_previews(preview_id, process_id, fingerprint, eligible_count,
                blocked_count, created_by, expires_at)
            VALUES (:id, :processId, :fingerprint, :eligible, :blocked, :actorId, :expiresAt)
            """, new MapSqlParameterSource().addValue("id", previewId).addValue("processId", processId)
            .addValue("fingerprint", calculation.fingerprint()).addValue("eligible", calculation.eligible().size())
            .addValue("blocked", calculation.blocked().size()).addValue("actorId", actor.id())
            .addValue("expiresAt", Timestamp.from(expiresAt)));
        audit(actor.id(), "PUBLICATION_PREVIEWED", previewId);
        return new PreviewView(previewId, processId, calculation.fingerprint(), expiresAt,
            calculation.eligible(), calculation.blocked(), calculation.skipped(), calculation.summary());
    }

    public BatchView create(UUID processId, CreateBatch command) {
        PrekinderActor actor = access.requireAdmin();
        if (command.idempotencyKey() == null || command.previewId() == null) {
            throw new IllegalArgumentException("La previsualización y la clave de idempotencia son obligatorias");
        }
        String mode = command.mode() == null ? "SCHEDULED" : command.mode().trim().toUpperCase();
        if (!List.of("IMMEDIATE", "SCHEDULED").contains(mode)) throw new IllegalArgumentException("Modo de publicación inválido");
        Instant scheduledAt = "IMMEDIATE".equals(mode) ? Instant.now() : command.scheduledAt();
        if (scheduledAt == null || ("SCHEDULED".equals(mode) && !scheduledAt.isAfter(Instant.now()))) {
            throw new IllegalArgumentException("La publicación programada debe tener una fecha futura");
        }
        return transactions.execute(status -> {
            List<BatchView> duplicate = findByIdempotency(processId, command.idempotencyKey());
            if (!duplicate.isEmpty()) return duplicate.getFirst();
            Map<String, Object> preview = jdbc.queryForMap("""
                SELECT fingerprint, expires_at FROM publication_previews
                 WHERE preview_id = :previewId AND process_id = :processId FOR UPDATE
                """, Map.of("previewId", command.previewId(), "processId", processId));
            Instant expiresAt = ((Timestamp) preview.get("expires_at")).toInstant();
            if (!expiresAt.isAfter(Instant.now())) {
                throw PrekinderDomainException.conflict("PUBLICATION_PREVIEW_EXPIRED",
                    "La previsualización venció; actualízala antes de publicar");
            }
            PreviewCalculation current = calculate(processId);
            if (!current.fingerprint().equals(preview.get("fingerprint"))) {
                throw PrekinderDomainException.conflict("PUBLICATION_PREVIEW_STALE",
                    "Las decisiones cambiaron desde la previsualización");
            }
            if (!current.blocked().isEmpty()) {
                throw PrekinderDomainException.conflict("PUBLICATION_BLOCKED",
                    "Resuelve las postulaciones bloqueadas antes de liberar resultados");
            }
            if (current.eligible().isEmpty()) {
                throw PrekinderDomainException.conflict("PUBLICATION_EMPTY", "No hay resultados nuevos para publicar");
            }
            UUID batchId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO publication_batches(batch_id, process_id, scheduled_at, created_by,
                    preview_id, idempotency_key, mode)
                VALUES (:id, :processId, :scheduledAt, :actorId, :previewId, :idempotencyKey, :mode)
                """, new MapSqlParameterSource().addValue("id", batchId).addValue("processId", processId)
                .addValue("scheduledAt", Timestamp.from(scheduledAt)).addValue("actorId", actor.id())
                .addValue("previewId", command.previewId()).addValue("idempotencyKey", command.idempotencyKey())
                .addValue("mode", mode));
            for (PreviewItem item : current.eligible()) {
                UUID communicationVersionId = publishedCommunicationVersion(processId,
                    item.rectification() ? "RESULT_RECTIFICATION" : resultEvent(item.decision()));
                jdbc.update("""
                    INSERT INTO publication_batch_items(item_id, batch_id, application_id,
                        decision_id, decision_snapshot, communication_template_version_id)
                    VALUES (:id, :batchId, :applicationId, :decisionId, CAST(:snapshot AS jsonb), :communicationVersionId)
                    """, Map.of("id", UUID.randomUUID(), "batchId", batchId,
                    "applicationId", item.applicationId(), "decisionId", item.decisionId(),
                    "communicationVersionId", communicationVersionId,
                    "snapshot", json(Map.of("decision", item.decision(), "version", item.decisionVersion(),
                        "rectification", item.rectification(), "communicationVersionId", communicationVersionId))));
                int updated = jdbc.update("""
                    UPDATE application_decisions_v2 SET status = 'SCHEDULED'
                     WHERE decision_id = :id AND status = 'DRAFT'
                    """, Map.of("id", item.decisionId()));
                if (updated != 1) throw new VersionConflictException("Una decisión cambió durante la publicación");
            }
            audit(actor.id(), "PUBLICATION_SCHEDULED", batchId);
            return batch(batchId);
        });
    }

    public List<BatchView> batches(UUID processId) {
        access.requireAdmin();
        return jdbc.query("""
            SELECT batch.batch_id, batch.process_id, batch.scheduled_at, batch.status,
                   batch.mode, batch.version, batch.created_at, batch.published_at,
                   count(item.item_id) AS item_count,
                   count(*) FILTER (WHERE item.status = 'EMAIL_SENT') AS sent_count,
                   count(*) FILTER (WHERE item.status = 'EMAIL_FAILED') AS failed_count,
                   batch.last_error_code
              FROM publication_batches batch
              LEFT JOIN publication_batch_items item ON item.batch_id = batch.batch_id
             WHERE batch.process_id = :id
             GROUP BY batch.batch_id ORDER BY batch.created_at DESC
            """, Map.of("id", processId), (rs, row) -> batchView(rs));
    }

    public BatchDetail detail(UUID batchId) {
        access.requireAdmin();
        BatchView batch = batch(batchId);
        List<BatchItemView> items = jdbc.query("""
            SELECT item.item_id, item.application_id, item.decision_id,
                   item.decision_snapshot ->> 'decision' AS decision, item.status, item.published_at,
                   coalesce(notification.attempts, 0) AS attempts,
                   notification.next_attempt_at, notification.last_error_code
              FROM publication_batch_items item
              LEFT JOIN notification_intents notification
                ON notification.batch_id = item.batch_id AND notification.application_id = item.application_id
             WHERE item.batch_id = :id ORDER BY item.application_id
            """, Map.of("id", batchId), (rs, row) -> new BatchItemView(
                rs.getObject("item_id", UUID.class), rs.getObject("application_id", UUID.class),
                rs.getObject("decision_id", UUID.class), rs.getString("decision"), rs.getString("status"),
                instant(rs.getTimestamp("published_at")), rs.getInt("attempts"),
                instant(rs.getTimestamp("next_attempt_at")), rs.getString("last_error_code")));
        return new BatchDetail(batch, items);
    }

    public BatchView cancel(UUID batchId, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            int updated = jdbc.update("""
                UPDATE publication_batches SET status = 'CANCELLED', version = version + 1
                 WHERE batch_id = :id AND version = :version AND status = 'SCHEDULED'
                   AND scheduled_at > now()
                """, Map.of("id", batchId, "version", expectedVersion));
            if (updated != 1) throw new VersionConflictException("El lote cambió o ya comenzó");
            jdbc.update("""
                UPDATE application_decisions_v2 SET status = 'DRAFT'
                 WHERE decision_id IN (SELECT decision_id FROM publication_batch_items WHERE batch_id = :id)
                   AND status = 'SCHEDULED'
                """, Map.of("id", batchId));
            audit(actor.id(), "PUBLICATION_CANCELLED", batchId);
            return batch(batchId);
        });
    }

    public BatchView retry(UUID batchId) {
        PrekinderActor actor = access.requireAdmin();
        int updated = jdbc.update("""
            UPDATE notification_intents SET status = 'PENDING', next_attempt_at = now(), last_error_code = NULL
             WHERE batch_id = :id AND status = 'FAILED' AND attempts < 5
            """, Map.of("id", batchId));
        if (updated == 0) throw PrekinderDomainException.conflict("PUBLICATION_NOT_RETRYABLE",
            "El lote no tiene entregas fallidas disponibles para reintento");
        jdbc.update("""
            UPDATE publication_batch_items SET status = 'EMAIL_PENDING'
             WHERE batch_id = :id AND status = 'EMAIL_FAILED'
            """, Map.of("id", batchId));
        jdbc.update("""
            UPDATE publication_batches SET status = 'PARTIAL', version = version + 1, last_error_code = NULL
             WHERE batch_id = :id AND status IN ('PARTIAL','PUBLISHED')
            """, Map.of("id", batchId));
        audit(actor.id(), "PUBLICATION_RETRY_REQUESTED", batchId);
        return batch(batchId);
    }

    private PreviewCalculation calculate(UUID processId) {
        List<Map<String, Object>> applications = jdbc.queryForList("""
            SELECT application.application_id, application.applicant_id, application.status AS application_status,
                   applicant.identity_ciphertext, applicant.identity_iv, applicant.identity_wrapped_dek,
                   applicant.identity_wrapped_dek_iv, applicant.identity_key_version,
                   decision.decision_id, decision.decision, decision.version AS decision_version,
                   decision.status AS decision_status, (decision.correction_of IS NOT NULL) AS rectification
              FROM applications application
              JOIN applicants applicant ON applicant.applicant_id = application.applicant_id
              LEFT JOIN application_decisions_v2 decision ON decision.application_id = application.application_id
                AND decision.status IN ('DRAFT','SCHEDULED','PUBLISHED')
             WHERE application.process_id = :id
               AND application.status NOT IN ('CANCELLED','INVALIDATED','DECLINED')
             ORDER BY application.application_id
            """, Map.of("id", processId));
        List<PreviewItem> eligible = new ArrayList<>();
        List<BlockedItem> blocked = new ArrayList<>();
        List<BlockedItem> skipped = new ArrayList<>();
        StringBuilder fingerprint = new StringBuilder(processId.toString());
        Map<String, Integer> summary = new LinkedHashMap<>(Map.of("ACCEPTED", 0, "WAITLIST", 0, "REJECTED", 0));
        for (Map<String, Object> row : applications) {
            UUID applicationId = (UUID) row.get("application_id");
            UUID decisionId = (UUID) row.get("decision_id");
            String decisionStatus = row.get("decision_status") == null ? null : String.valueOf(row.get("decision_status"));
            String reason = null;
            if (decisionId == null) reason = "MISSING_DECISION";
            else if (!"DRAFT".equals(decisionStatus)) {
                skipped.add(new BlockedItem(applicationId, "ALREADY_RELEASED_OR_SCHEDULED"));
                fingerprint.append('|').append(applicationId).append(":SKIPPED:").append(decisionStatus);
                continue;
            }
            else if (!hasRecipient(row, applicationId)) reason = "RECIPIENT_MISSING";
            if (reason != null) {
                blocked.add(new BlockedItem(applicationId, reason));
                fingerprint.append('|').append(applicationId).append(':').append(reason);
                continue;
            }
            String decision = String.valueOf(row.get("decision"));
            int version = ((Number) row.get("decision_version")).intValue();
            boolean rectification = Boolean.TRUE.equals(row.get("rectification"));
            eligible.add(new PreviewItem(applicationId, decisionId, decision, version, rectification));
            summary.computeIfPresent(decision, (key, count) -> count + 1);
            fingerprint.append('|').append(applicationId).append(':').append(decisionId)
                .append(':').append(decision).append(':').append(version);
        }
        return new PreviewCalculation(hash(fingerprint.toString()), eligible, blocked, skipped, summary);
    }

    private boolean hasRecipient(Map<String, Object> row, UUID applicationId) {
        try {
            UUID applicantId = (UUID) row.get("applicant_id");
            String plaintext = encryption.decrypt(new EncryptedPayload(String.valueOf(row.get("identity_ciphertext")),
                String.valueOf(row.get("identity_iv")), String.valueOf(row.get("identity_wrapped_dek")),
                String.valueOf(row.get("identity_wrapped_dek_iv")), String.valueOf(row.get("identity_key_version"))),
                "prekinder|applicants|" + applicantId + "|application:" + applicationId + "|identity");
            @SuppressWarnings("unchecked")
            Map<String, Object> identity = mapper.readValue(plaintext, Map.class);
            Set<String> recipients = PrekinderPublicationWorker.parentEmails(identity);
            return !recipients.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private UUID publishedCommunicationVersion(UUID processId, String eventCode) {
        List<UUID> versions = jdbc.queryForList("""
            SELECT version.communication_template_version_id
              FROM prekinder_communication_templates template
              JOIN prekinder_communication_template_versions version
                ON version.communication_template_id = template.communication_template_id
             WHERE template.process_id = :processId AND template.event_code = :eventCode
               AND template.status = 'ACTIVE' AND version.status = 'PUBLISHED'
            """, Map.of("processId", processId, "eventCode", eventCode), UUID.class);
        if (versions.isEmpty()) throw PrekinderDomainException.conflict("COMMUNICATION_MISSING",
            "Publica la plantilla de comunicación requerida antes de crear el lote");
        return versions.getFirst();
    }

    private static String resultEvent(String decision) {
        return switch (decision) {
            case "ACCEPTED" -> "RESULT_ACCEPTED";
            case "WAITLIST" -> "RESULT_WAITLIST";
            default -> "RESULT_REJECTED";
        };
    }

    private List<BatchView> findByIdempotency(UUID processId, UUID idempotencyKey) {
        return jdbc.query("""
            SELECT batch.batch_id, batch.process_id, batch.scheduled_at, batch.status,
                   batch.mode, batch.version, batch.created_at, batch.published_at,
                   count(item.item_id) AS item_count,
                   count(*) FILTER (WHERE item.status = 'EMAIL_SENT') AS sent_count,
                   count(*) FILTER (WHERE item.status = 'EMAIL_FAILED') AS failed_count,
                   batch.last_error_code
              FROM publication_batches batch
              LEFT JOIN publication_batch_items item ON item.batch_id = batch.batch_id
             WHERE batch.process_id = :processId AND batch.idempotency_key = :key
             GROUP BY batch.batch_id
            """, Map.of("processId", processId, "key", idempotencyKey), (rs, row) -> batchView(rs));
    }

    private BatchView batch(UUID batchId) {
        return jdbc.queryForObject("""
            SELECT batch.batch_id, batch.process_id, batch.scheduled_at, batch.status,
                   batch.mode, batch.version, batch.created_at, batch.published_at,
                   count(item.item_id) AS item_count,
                   count(*) FILTER (WHERE item.status = 'EMAIL_SENT') AS sent_count,
                   count(*) FILTER (WHERE item.status = 'EMAIL_FAILED') AS failed_count,
                   batch.last_error_code
              FROM publication_batches batch
              LEFT JOIN publication_batch_items item ON item.batch_id = batch.batch_id
             WHERE batch.batch_id = :id GROUP BY batch.batch_id
            """, Map.of("id", batchId), (rs, row) -> batchView(rs));
    }

    private static BatchView batchView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BatchView(rs.getObject("batch_id", UUID.class), rs.getObject("process_id", UUID.class),
            instant(rs.getTimestamp("scheduled_at")), rs.getString("status"), rs.getString("mode"),
            rs.getLong("version"), rs.getLong("item_count"), rs.getLong("sent_count"),
            rs.getLong("failed_count"), instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("published_at")), rs.getString("last_error_code"));
    }

    private void audit(UUID actorId, String action, UUID aggregateId) {
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result)
            VALUES (:id, :actorId, :action, 'PUBLICATION_BATCH', :aggregateId, 'SUCCESS')
            """, Map.of("id", UUID.randomUUID(), "actorId", actorId, "action", action,
                "aggregateId", aggregateId));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("No fue posible preparar la publicación", exception); }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private record PreviewCalculation(String fingerprint, List<PreviewItem> eligible,
        List<BlockedItem> blocked, List<BlockedItem> skipped, Map<String, Integer> summary) {}
    public record CreateBatch(UUID previewId, UUID idempotencyKey, String mode, Instant scheduledAt) {}
    public record PreviewItem(UUID applicationId, UUID decisionId, String decision,
        int decisionVersion, boolean rectification) {}
    public record BlockedItem(UUID applicationId, String reasonCode) {}
    public record PreviewView(UUID previewId, UUID processId, String fingerprint, Instant expiresAt,
        List<PreviewItem> eligible, List<BlockedItem> blocked, List<BlockedItem> skipped,
        Map<String, Integer> summary) {}
    public record BatchView(UUID batchId, UUID processId, Instant scheduledAt, String status, String mode,
        long version, long itemCount, long sentCount, long failedCount, Instant createdAt,
        Instant publishedAt, String lastErrorCode) {}
    public record BatchItemView(UUID itemId, UUID applicationId, UUID decisionId, String decision,
        String status, Instant publishedAt, int attempts, Instant nextAttemptAt, String lastErrorCode) {}
    public record BatchDetail(BatchView batch, List<BatchItemView> items) {}
}
