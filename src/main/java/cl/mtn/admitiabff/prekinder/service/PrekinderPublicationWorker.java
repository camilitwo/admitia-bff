package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.service.notification.ResendEmailSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderPublicationWorker {
    private static final Logger log = LoggerFactory.getLogger(PrekinderPublicationWorker.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final EnvelopeEncryptionService encryption;
    private final ObjectMapper mapper;
    private final ResendEmailSender sender;
    private final boolean mockMode;
    private final String portalUrl;

    public PrekinderPublicationWorker(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        EnvelopeEncryptionService encryption, ObjectMapper mapper, ResendEmailSender sender,
        @Value("${app.email.mock-mode:false}") boolean mockMode,
        @Value("${app.frontend.url:https://admitia.cl}") String portalUrl) {
        this.jdbc = jdbc; this.encryption = encryption; this.mapper = mapper; this.sender = sender;
        this.mockMode = mockMode; this.portalUrl = portalUrl;
    }

    @Scheduled(fixedDelayString = "${app.prekinder.publication.worker-delay-ms:3000}")
    public void publishDueBatches() {
        List<UUID> due = jdbc.queryForList("""
            SELECT batch_id FROM publication_batches
             WHERE status = 'SCHEDULED' AND scheduled_at <= now() ORDER BY scheduled_at LIMIT 5
            """, Map.of(), UUID.class);
        due.forEach(this::publishBatch);
        dispatchPendingEmails();
        dispatchScheduleEmails();
    }

    private void publishBatch(UUID batchId) {
        int claimed = jdbc.update("""
            UPDATE publication_batches SET status = 'PROCESSING', version = version + 1
             WHERE batch_id = :id AND status = 'SCHEDULED'
            """, Map.of("id", batchId));
        if (claimed != 1) return;
        try {
            List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT i.item_id, i.application_id, i.decision_id, d.decision,
                       (d.correction_of IS NOT NULL) AS rectification
                  FROM publication_batch_items i JOIN application_decisions_v2 d ON d.decision_id = i.decision_id
                 WHERE i.batch_id = :id AND i.status = 'PENDING'
                """, Map.of("id", batchId));
            for (Map<String, Object> item : items) {
                UUID applicationId = (UUID) item.get("application_id");
                UUID decisionId = (UUID) item.get("decision_id");
                String decision = String.valueOf(item.get("decision"));
                jdbc.update("""
                    UPDATE publication_batch_items SET status = 'EMAIL_PENDING', published_at = now()
                     WHERE item_id = :id
                    """, Map.of("id", item.get("item_id")));
                jdbc.update("UPDATE application_decisions_v2 SET status = 'PUBLISHED' WHERE decision_id = :id",
                    Map.of("id", decisionId));
                jdbc.update("""
                    UPDATE applications SET status = :status, version = version + 1, updated_at = now()
                     WHERE application_id = :id
                    """, Map.of("id", applicationId, "status", applicationStatus(decision)));
                if ("ACCEPTED".equals(decision)) createOffer(applicationId);
                String template = Boolean.TRUE.equals(item.get("rectification"))
                    ? "PREKINDER_RESULT_RECTIFICATION" : "PREKINDER_RESULT";
                jdbc.update("""
                    INSERT INTO notification_intents(notification_id, application_id, template_code, channel,
                        status, idempotency_key, batch_id, next_attempt_at)
                    VALUES (:id, :applicationId, :template, 'EMAIL', 'PENDING', :key, :batchId, now())
                    ON CONFLICT (idempotency_key) DO NOTHING
                    """, Map.of("id", UUID.randomUUID(), "applicationId", applicationId,
                    "template", template, "key", "prekinder-result:" + decisionId, "batchId", batchId));
            }
            jdbc.update("""
                UPDATE publication_batches SET status = 'PUBLISHED', published_at = now(), version = version + 1
                 WHERE batch_id = :id
                """, Map.of("id", batchId));
            log.info("Lote Prekínder publicado batchId={} items={}", batchId, items.size());
        } catch (RuntimeException exception) {
            jdbc.update("""
                UPDATE publication_batches SET status = 'PARTIAL', version = version + 1,
                    last_error_code = 'PUBLICATION_FAILED' WHERE batch_id = :id
                """,
                Map.of("id", batchId));
            log.error("Falló publicación Prekínder batchId={} code=PUBLICATION_FAILED", batchId);
        }
    }

    private void dispatchPendingEmails() {
        List<Map<String, Object>> intents = jdbc.queryForList("""
            SELECT n.notification_id, n.application_id, n.batch_id, n.template_code, d.decision,
                   batch.process_id, process.name AS process_name,
                   bi.communication_template_version_id,
                   a.applicant_id, ap.identity_ciphertext, ap.identity_iv, ap.identity_wrapped_dek,
                   ap.identity_wrapped_dek_iv, ap.identity_key_version
              FROM notification_intents n JOIN applications a ON a.application_id = n.application_id
              JOIN applicants ap ON ap.applicant_id = a.applicant_id
              JOIN publication_batch_items bi ON bi.batch_id = n.batch_id AND bi.application_id = n.application_id
              JOIN publication_batches batch ON batch.batch_id = n.batch_id
              JOIN admission_processes process ON process.process_id = batch.process_id
              JOIN application_decisions_v2 d ON d.decision_id = bi.decision_id
             WHERE n.status IN ('PENDING','FAILED') AND n.attempts < 5
               AND coalesce(n.next_attempt_at, now()) <= now()
             ORDER BY n.created_at LIMIT 25
            """, Map.of());
        for (Map<String, Object> intent : intents) dispatch(intent);
    }

    private void dispatch(Map<String, Object> row) {
        UUID notificationId = (UUID) row.get("notification_id");
        try {
            UUID applicationId = (UUID) row.get("application_id");
            UUID applicantId = (UUID) row.get("applicant_id");
            String plaintext = encryption.decrypt(new EncryptedPayload(String.valueOf(row.get("identity_ciphertext")),
                String.valueOf(row.get("identity_iv")), String.valueOf(row.get("identity_wrapped_dek")),
                String.valueOf(row.get("identity_wrapped_dek_iv")), String.valueOf(row.get("identity_key_version"))),
                "prekinder|applicants|" + applicantId + "|application:" + applicationId + "|identity");
            @SuppressWarnings("unchecked")
            Map<String, Object> identity = mapper.readValue(plaintext, Map.class);
            Set<String> emails = parentEmails(identity);
            if (emails.isEmpty()) throw new IllegalStateException("RECIPIENT_MISSING");
            String decision = String.valueOf(row.get("decision"));
            String eventCode = "PREKINDER_RESULT_RECTIFICATION".equals(row.get("template_code"))
                ? "RESULT_RECTIFICATION" : resultEvent(decision);
            CommunicationContent content = communicationSnapshot(
                (UUID) row.get("communication_template_version_id"), (UUID) row.get("process_id"), eventCode);
            String applicantName = applicantName(identity);
            String processName = escapeHtml(String.valueOf(row.get("process_name")));
            String subject = PrekinderCommunicationTemplateService.render(content.subject(), applicantName,
                processName, portalUrl, "");
            String body = PrekinderCommunicationTemplateService.render(content.bodyHtml(), applicantName,
                processName, portalUrl, "");
            if (!mockMode) for (String email : emails) sender.send(email, subject, body);
            jdbc.update("""
                UPDATE notification_intents SET status = 'SENT', sent_at = now(), attempts = attempts + 1,
                    last_error_code = NULL WHERE notification_id = :id
                """, Map.of("id", notificationId));
            jdbc.update("""
                UPDATE publication_batch_items SET status = 'EMAIL_SENT'
                 WHERE batch_id = :batchId AND application_id = :applicationId
                """, Map.of("batchId", row.get("batch_id"), "applicationId", applicationId));
            jdbc.update("""
                UPDATE publication_batches SET status = 'PUBLISHED', last_error_code = NULL,
                    version = version + 1
                 WHERE batch_id = :batchId
                   AND NOT EXISTS (
                       SELECT 1 FROM publication_batch_items
                        WHERE batch_id = :batchId AND status <> 'EMAIL_SENT'
                   )
                """, Map.of("batchId", row.get("batch_id")));
        } catch (Exception exception) {
            jdbc.update("""
                UPDATE notification_intents SET status = 'FAILED', attempts = attempts + 1,
                    next_attempt_at = :retryAt, last_error_code = 'EMAIL_PROVIDER_ERROR'
                 WHERE notification_id = :id
                """, new MapSqlParameterSource().addValue("id", notificationId)
                .addValue("retryAt", Timestamp.from(Instant.now().plus(5, ChronoUnit.MINUTES))));
            jdbc.update("""
                UPDATE publication_batch_items SET status = 'EMAIL_FAILED'
                 WHERE batch_id = :batchId AND application_id = :applicationId
                """, Map.of("batchId", row.get("batch_id"), "applicationId", row.get("application_id")));
            jdbc.update("""
                UPDATE publication_batches SET status = 'PARTIAL', last_error_code = 'EMAIL_PROVIDER_ERROR',
                    version = version + 1 WHERE batch_id = :batchId
                """, Map.of("batchId", row.get("batch_id")));
            log.warn("Falló correo Prekínder notificationId={} code=EMAIL_PROVIDER_ERROR", notificationId);
        }
    }

    private void dispatchScheduleEmails() {
        List<Map<String, Object>> intents = jdbc.queryForList("""
            SELECT n.notification_id, n.application_id, n.recipient_actor_id, n.template_code,
                   n.payload::text AS payload_text, a.applicant_id,
                   ap.identity_ciphertext, ap.identity_iv, ap.identity_wrapped_dek,
                   ap.identity_wrapped_dek_iv, ap.identity_key_version,
                   p.email AS professional_email
              FROM notification_intents n
              LEFT JOIN applications a ON a.application_id = n.application_id
              LEFT JOIN applicants ap ON ap.applicant_id = a.applicant_id
              LEFT JOIN professional_profiles p ON p.professional_id = n.recipient_actor_id
             WHERE n.batch_id IS NULL AND n.template_code IN ('PREKINDER_GROUP_ASSIGNED','PREKINDER_GROUP_RESCHEDULED')
               AND n.status IN ('PENDING','FAILED') AND n.attempts < 5
               AND coalesce(n.next_attempt_at, now()) <= now()
             ORDER BY n.created_at LIMIT 50
            """, Map.of());
        for (Map<String, Object> intent : intents) dispatchSchedule(intent);
    }

    private void dispatchSchedule(Map<String, Object> row) {
        UUID notificationId = (UUID) row.get("notification_id");
        try {
            Set<String> recipients = new LinkedHashSet<>();
            if (row.get("application_id") != null) {
                UUID applicationId = (UUID) row.get("application_id");
                UUID applicantId = (UUID) row.get("applicant_id");
                String plaintext = encryption.decrypt(new EncryptedPayload(String.valueOf(row.get("identity_ciphertext")),
                    String.valueOf(row.get("identity_iv")), String.valueOf(row.get("identity_wrapped_dek")),
                    String.valueOf(row.get("identity_wrapped_dek_iv")), String.valueOf(row.get("identity_key_version"))),
                    "prekinder|applicants|" + applicantId + "|application:" + applicationId + "|identity");
                @SuppressWarnings("unchecked")
                Map<String, Object> identity = mapper.readValue(plaintext, Map.class);
                recipients.addAll(parentEmails(identity));
            } else if (row.get("professional_email") != null) {
                String email = String.valueOf(row.get("professional_email")).trim();
                if (!email.isBlank()) recipients.add(email);
            }
            if (recipients.isEmpty()) throw new IllegalStateException("RECIPIENT_MISSING");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = mapper.readValue(String.valueOf(row.get("payload_text")), Map.class);
            boolean rescheduled = "PREKINDER_GROUP_RESCHEDULED".equals(row.get("template_code"));
            String subject = rescheduled ? "Reagendamiento evaluación Prekínder" : "Asignación evaluación Prekínder";
            String body = scheduleEmailBody(payload, rescheduled);
            if (!mockMode) for (String recipient : recipients) sender.send(recipient, subject, body);
            markSent(notificationId);
        } catch (Exception exception) {
            markFailed(notificationId);
            log.warn("Falló notificación de agenda Prekínder notificationId={} code=SCHEDULE_EMAIL_FAILED", notificationId);
        }
    }

    static Set<String> parentEmails(Map<String, Object> identity) {
        Set<String> recipients = new LinkedHashSet<>();
        for (String field : List.of("fatherEmail", "motherEmail", "familyEmail")) {
            Object value = identity.get(field);
            if (value != null && !String.valueOf(value).isBlank()) recipients.add(String.valueOf(value).trim().toLowerCase());
        }
        return recipients;
    }

    private void markSent(UUID notificationId) {
        jdbc.update("""
            UPDATE notification_intents SET status = 'SENT', sent_at = now(), attempts = attempts + 1,
                last_error_code = NULL WHERE notification_id = :id
            """, Map.of("id", notificationId));
    }

    private void markFailed(UUID notificationId) {
        jdbc.update("""
            UPDATE notification_intents SET status = 'FAILED', attempts = attempts + 1,
                next_attempt_at = :retryAt, last_error_code = 'EMAIL_PROVIDER_ERROR'
             WHERE notification_id = :id
            """, new MapSqlParameterSource().addValue("id", notificationId)
            .addValue("retryAt", Timestamp.from(Instant.now().plus(5, ChronoUnit.MINUTES))));
    }

    static String scheduleEmailBody(Map<String, Object> payload, boolean rescheduled) {
        ZoneId zone = ZoneId.of("America/Santiago");
        ZonedDateTime startsAt = Instant.parse(String.valueOf(payload.get("startsAt"))).atZone(zone);
        ZonedDateTime endsAt = Instant.parse(String.valueOf(payload.get("endsAt"))).atZone(zone);
        DateTimeFormatter date = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-CL"));
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("es-CL"));
        String title = rescheduled ? "Nueva asignación de evaluación Prekínder" : "Asignación de evaluación Prekínder";
        String notice = rescheduled ? "La evaluación fue reagendada. Considera estos nuevos antecedentes:" : "El postulante fue asignado al siguiente grupo:";
        return """
            <div style="font-family:Arial,sans-serif;color:#102b57;line-height:1.6">
              <h1>%s</h1>
              <p>%s</p>
              <ul>
                <li><strong>Fecha:</strong> %s</li>
                <li><strong>Horario:</strong> %s a %s horas</li>
                <li><strong>Ubicación:</strong> %s (%s)</li>
                <li><strong>Grupo:</strong> %s</li>
              </ul>
              <p>Recomendamos llegar con anticipación al establecimiento.</p>
            </div>
            """.formatted(title, notice, startsAt.format(date), startsAt.format(time), endsAt.format(time),
                payload.getOrDefault("roomName", "Sala por confirmar"), payload.getOrDefault("roomCode", ""),
                payload.getOrDefault("groupCode", ""));
    }

    private static String applicationStatus(String decision) {
        return switch (decision) {
            case "ACCEPTED" -> "ACCEPTED";
            case "WAITLIST" -> "WAITLISTED";
            default -> "NOT_ADMITTED";
        };
    }

    private void createOffer(UUID applicationId) {
        UUID offerId = UUID.randomUUID();
        int inserted = jdbc.update("""
            INSERT INTO offers(offer_id, application_id, status, expires_at)
            VALUES (:id, :applicationId, 'OFFERED', now() + interval '7 days')
            ON CONFLICT (application_id) WHERE status = 'OFFERED' DO NOTHING
            """, Map.of("id", offerId, "applicationId", applicationId));
        if (inserted == 1) jdbc.update("""
            INSERT INTO offer_status_history(offer_history_id, offer_id, to_status, reason_code)
            VALUES (:id, :offerId, 'OFFERED', 'RESULT_PUBLISHED')
            """, Map.of("id", UUID.randomUUID(), "offerId", offerId));
    }

    private CommunicationContent communicationSnapshot(UUID versionId, UUID processId, String eventCode) {
        String versionPredicate = versionId == null
            ? "AND version.status = 'PUBLISHED'"
            : "AND version.communication_template_version_id = :versionId AND version.status IN ('PUBLISHED','SUPERSEDED')";
        Map<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("processId", processId); parameters.put("eventCode", eventCode);
        if (versionId != null) parameters.put("versionId", versionId);
        List<CommunicationContent> rows = jdbc.query("""
            SELECT version.subject, version.body_html
              FROM prekinder_communication_templates template
              JOIN prekinder_communication_template_versions version
                ON version.communication_template_id = template.communication_template_id
             WHERE template.process_id = :processId AND template.event_code = :eventCode
            """ + versionPredicate, parameters,
            (rs, row) -> new CommunicationContent(rs.getString("subject"), rs.getString("body_html")));
        if (rows.isEmpty()) throw new IllegalStateException("COMMUNICATION_MISSING");
        return rows.getFirst();
    }

    private static String resultEvent(String decision) {
        return switch (decision) {
            case "ACCEPTED" -> "RESULT_ACCEPTED";
            case "WAITLIST" -> "RESULT_WAITLIST";
            default -> "RESULT_REJECTED";
        };
    }

    private static String applicantName(Map<String, Object> identity) {
        return escapeHtml((String.valueOf(identity.getOrDefault("firstName", "")) + " "
            + String.valueOf(identity.getOrDefault("paternalLastName", ""))).trim());
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private record CommunicationContent(String subject, String bodyHtml) {}
}
