package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
public class PrekinderInclusionService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final EnvelopeEncryptionService encryption;
    private final ObjectMapper mapper;

    public PrekinderInclusionService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
                                     @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
                                     PrekinderAccessService access, EnvelopeEncryptionService encryption,
                                     ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.encryption = encryption;
        this.mapper = mapper;
    }

    public InclusionView get(UUID applicationId) {
        PrekinderActor actor = access.requireActor();
        ApplicationAccess application = assertOwner(applicationId, actor);
        return read(applicationId, application.inclusionEnabled());
    }

    public InclusionView save(UUID applicationId, Map<String, Object> payload) {
        PrekinderActor actor = access.requireActor();
        return transactions.execute(status -> {
            ApplicationAccess application = assertOwner(applicationId, actor);
            if (!application.inclusionEnabled()) {
                throw PrekinderDomainException.conflict("INCLUSION_DISABLED",
                    "La ruta de inclusión no está habilitada para esta campaña");
            }
            if (application.paymentRequired() && !"PAID".equals(application.paymentStatus())) {
                throw PrekinderDomainException.forbidden("APPLICATION_PAYMENT_REQUIRED",
                    "Debe confirmar el pago de postulación antes de declarar antecedentes de inclusión");
            }
            boolean submitted = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("isSubmitted", false)));
            boolean consentAccepted = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("consentAccepted", false)));
            if (submitted && !consentAccepted) {
                throw new IllegalArgumentException("El consentimiento es obligatorio para enviar la declaración de inclusión");
            }

            UUID inclusionId = lockOrCreate(applicationId);
            boolean correction = validateCorrectionScope(applicationId, inclusionId, payload);
            Integer nextRevision = jdbc.queryForObject("""
                SELECT COALESCE(max(revision_number), 0) + 1
                  FROM inclusion_record_revisions WHERE inclusion_id = :id
                """, Map.of("id", inclusionId), Integer.class);
            EncryptedPayload encrypted = encrypt(applicationId, payload);
            jdbc.update("""
                INSERT INTO inclusion_record_revisions(inclusion_revision_id, inclusion_id, revision_number,
                    state, ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version, authored_by)
                VALUES (:revisionId, :inclusionId, :revision, :state, :ciphertext, :iv,
                    :wrappedDek, :wrappedDekIv, :keyVersion, :actorId)
                """, new MapSqlParameterSource().addValue("revisionId", UUID.randomUUID())
                .addValue("inclusionId", inclusionId).addValue("revision", nextRevision)
                .addValue("state", submitted ? "SUBMITTED" : "DRAFT")
                .addValue("ciphertext", encrypted.ciphertext()).addValue("iv", encrypted.iv())
                .addValue("wrappedDek", encrypted.wrappedDek()).addValue("wrappedDekIv", encrypted.wrappedDekIv())
                .addValue("keyVersion", encrypted.keyVersion()).addValue("actorId", actor.id()));
            jdbc.update("""
                UPDATE inclusion_records
                   SET consent_status = :consentStatus, declared_at = CASE WHEN :submitted THEN now() ELSE declared_at END,
                       specific_interview_required = :submitted,
                       specific_interview_status = CASE
                           WHEN :submitted AND specific_interview_status = 'NOT_REQUIRED' THEN 'PENDING'
                           WHEN NOT :submitted THEN 'NOT_REQUIRED'
                           ELSE specific_interview_status END,
                       version = version + 1, updated_at = now()
                 WHERE inclusion_id = :id
                """, new MapSqlParameterSource().addValue("id", inclusionId).addValue("submitted", submitted)
                .addValue("consentStatus", consentAccepted ? "ACCEPTED" : "PENDING"));
            if (submitted) appendConsent(applicationId);
            if (submitted && correction) {
                jdbc.update("""
                    UPDATE application_correction_requests SET status = 'COMPLETED', completed_at = now()
                     WHERE application_id = :id AND status = 'OPEN'
                       AND NOT EXISTS (
                           SELECT 1 FROM jsonb_array_elements_text(allowed_fields) field
                            WHERE field NOT LIKE 'inclusion.%'
                       )
                       AND jsonb_array_length(allowed_document_categories) = 0
                    """, Map.of("id", applicationId));
            }
            audit(actor.id(), submitted ? "INCLUSION_DECLARATION_SUBMITTED" : "INCLUSION_DECLARATION_SAVED",
                applicationId, Map.of("revision", nextRevision));
            return read(applicationId, true);
        });
    }

    public InclusionView updateInterview(UUID applicationId, String requestedStatus, String reason) {
        PrekinderActor actor = access.requireSensitiveAccess();
        String normalized = requestedStatus == null ? "" : requestedStatus.trim().toUpperCase();
        if (!List.of("PENDING", "SCHEDULED", "COMPLETED", "WAIVED").contains(normalized)) {
            throw new IllegalArgumentException("Estado de entrevista específica no válido");
        }
        if ("WAIVED".equals(normalized) && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("La excepción de entrevista requiere motivo");
        }
        int updated = jdbc.update("""
            UPDATE inclusion_records SET specific_interview_status = :status,
                version = version + 1, updated_at = now()
             WHERE application_id = :applicationId AND specific_interview_required
            """, Map.of("status", normalized, "applicationId", applicationId));
        if (updated != 1) throw PrekinderDomainException.conflict("INCLUSION_INTERVIEW_NOT_REQUIRED",
            "La postulación no posee una declaración de inclusión enviada");
        audit(actor.id(), "INCLUSION_INTERVIEW_STATUS_CHANGED", applicationId,
            reason == null || reason.isBlank() ? Map.of("status", normalized)
                : Map.of("status", normalized, "reason", reason.trim()));
        return read(applicationId, true);
    }

    private UUID lockOrCreate(UUID applicationId) {
        List<UUID> current = jdbc.queryForList("""
            SELECT inclusion_id FROM inclusion_records WHERE application_id = :id FOR UPDATE
            """, Map.of("id", applicationId), UUID.class);
        if (!current.isEmpty()) return current.getFirst();
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO inclusion_records(inclusion_id, application_id, consent_status)
            VALUES (:id, :applicationId, 'PENDING')
            """, Map.of("id", id, "applicationId", applicationId));
        return id;
    }

    private boolean validateCorrectionScope(UUID applicationId, UUID inclusionId, Map<String, Object> next) {
        List<EncryptedPayload> revisions = jdbc.query("""
            SELECT ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version
              FROM inclusion_record_revisions
             WHERE inclusion_id = :id AND state = 'SUBMITTED'
             ORDER BY revision_number DESC LIMIT 1
            """, Map.of("id", inclusionId), (rs, row) -> new EncryptedPayload(
                rs.getString("ciphertext"), rs.getString("iv"), rs.getString("wrapped_dek"),
                rs.getString("wrapped_dek_iv"), rs.getString("key_version")));
        if (revisions.isEmpty()) return false;

        List<String> allowed = jdbc.query("""
            SELECT field FROM application_correction_requests request,
                 LATERAL jsonb_array_elements_text(request.allowed_fields) field
             WHERE request.application_id = :id AND request.status = 'OPEN'
               AND field LIKE 'inclusion.%'
            """, Map.of("id", applicationId), (rs, row) -> rs.getString(1).substring("inclusion.".length()));
        if (allowed.isEmpty()) {
            throw PrekinderDomainException.conflict("INCLUSION_DECLARATION_LOCKED",
                "La declaración de inclusión ya fue enviada");
        }
        Map<String, Object> current = decrypt(applicationId, revisions.getFirst());
        Set<String> keys = new HashSet<>(current.keySet());
        keys.addAll(next.keySet());
        keys.remove("isSubmitted");
        for (String key : keys) {
            if (!Objects.equals(mapper.valueToTree(current.get(key)), mapper.valueToTree(next.get(key)))
                && !allowed.contains(key)) {
                throw PrekinderDomainException.forbidden("INCLUSION_FIELD_NOT_REOPENED",
                    "El antecedente de inclusión " + key + " no fue habilitado para corrección");
            }
        }
        return true;
    }

    private void appendConsent(UUID applicationId) {
        Integer version = jdbc.queryForObject("""
            SELECT COALESCE(max(version), 0) + 1 FROM consent_records
             WHERE application_id = :id AND consent_type = 'INCLUSION'
            """, Map.of("id", applicationId), Integer.class);
        jdbc.update("""
            INSERT INTO consent_records(consent_id, application_id, consent_type, status, version, accepted_at)
            VALUES (:id, :applicationId, 'INCLUSION', 'ACCEPTED', :version, now())
            """, Map.of("id", UUID.randomUUID(), "applicationId", applicationId, "version", version));
    }

    private InclusionView read(UUID applicationId, boolean enabled) {
        List<InclusionView> rows = jdbc.query("""
            SELECT record.inclusion_id, record.consent_status, record.specific_interview_required,
                   record.specific_interview_status, record.declared_at, record.version,
                   revision.revision_number, revision.state, revision.ciphertext, revision.iv,
                   revision.wrapped_dek, revision.wrapped_dek_iv, revision.key_version
              FROM inclusion_records record
              LEFT JOIN LATERAL (
                  SELECT item.* FROM inclusion_record_revisions item
                   WHERE item.inclusion_id = record.inclusion_id
                   ORDER BY item.revision_number DESC LIMIT 1
              ) revision ON true
             WHERE record.application_id = :applicationId
            """, Map.of("applicationId", applicationId), (rs, row) -> {
                Integer revisionNumber = rs.getObject("revision_number", Integer.class);
                Map<String, Object> declaration = revisionNumber == null ? Map.of() : decrypt(applicationId,
                    new EncryptedPayload(rs.getString("ciphertext"), rs.getString("iv"),
                        rs.getString("wrapped_dek"), rs.getString("wrapped_dek_iv"), rs.getString("key_version")));
                Timestamp declaredAt = rs.getTimestamp("declared_at");
                return new InclusionView(enabled, true, rs.getObject("inclusion_id", UUID.class),
                    rs.getString("consent_status"), rs.getBoolean("specific_interview_required"),
                    rs.getString("specific_interview_status"), declaredAt == null ? null : declaredAt.toInstant(),
                    rs.getLong("version"), revisionNumber, rs.getString("state"), declaration);
            });
        return rows.isEmpty() ? new InclusionView(enabled, false, null, "PENDING", false, "NOT_REQUIRED",
            null, 0, null, null, Map.of()) : rows.getFirst();
    }

    private ApplicationAccess assertOwner(UUID applicationId, PrekinderActor actor) {
        List<ApplicationAccess> rows = jdbc.query("""
            SELECT config.inclusion_enabled, application.payment_required, application.payment_status
              FROM applications application
              JOIN applicants applicant ON applicant.applicant_id = application.applicant_id
              JOIN families family ON family.family_id = applicant.family_id
              JOIN prekinder_process_configuration config ON config.process_id = application.process_id
             WHERE application.application_id = :applicationId AND family.external_reference = :actorReference
            """, Map.of("applicationId", applicationId, "actorReference", actor.id().toString()),
            (rs, row) -> new ApplicationAccess(rs.getBoolean("inclusion_enabled"),
                rs.getBoolean("payment_required"), rs.getString("payment_status")));
        if (rows.isEmpty()) throw PrekinderDomainException.forbidden("APPLICATION_NOT_OWNED",
            "Postulación Prekínder no autorizada");
        return rows.getFirst();
    }

    private EncryptedPayload encrypt(UUID applicationId, Map<String, Object> payload) {
        try { return encryption.encrypt(mapper.writeValueAsString(payload), aad(applicationId)); }
        catch (Exception exception) { throw new IllegalArgumentException("Declaración de inclusión no válida", exception); }
    }

    private Map<String, Object> decrypt(UUID applicationId, EncryptedPayload payload) {
        try {
            return new LinkedHashMap<>(mapper.readValue(encryption.decrypt(payload, aad(applicationId)),
                new TypeReference<>() {}));
        } catch (Exception exception) {
            throw new IllegalStateException("Declaración de inclusión cifrada no válida", exception);
        }
    }

    private void audit(UUID actorId, String action, UUID applicationId, Map<String, ?> metadata) {
        try {
            jdbc.update("""
                INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result, metadata)
                VALUES (:id, :actorId, :action, 'INCLUSION_RECORD', :applicationId, 'SUCCESS', CAST(:metadata AS jsonb))
                """, Map.of("id", UUID.randomUUID(), "actorId", actorId, "action", action,
                    "applicationId", applicationId, "metadata", mapper.writeValueAsString(metadata)));
        } catch (Exception exception) {
            throw new IllegalStateException("No fue posible auditar la declaración de inclusión", exception);
        }
    }

    private static String aad(UUID applicationId) { return "prekinder|inclusion|application:" + applicationId; }
    private record ApplicationAccess(boolean inclusionEnabled, boolean paymentRequired, String paymentStatus) {}
    public record InclusionView(boolean enabled, boolean declared, UUID inclusionId, String consentStatus,
        boolean specificInterviewRequired, String specificInterviewStatus, Instant declaredAt, long version,
        Integer revisionNumber, String revisionState, Map<String, Object> declaration) {}
}
