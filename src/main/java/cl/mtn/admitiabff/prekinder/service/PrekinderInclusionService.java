package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
        return read(applicationId, application.inclusionEnabled(), applicant(applicationId));
    }

    public AdminInclusionView getForAdmin(UUID applicationId) {
        PrekinderActor actor = access.requireSensitiveAccess();
        ApplicationAccess application = applicationAccess(applicationId);
        InclusionView current = read(applicationId, application.inclusionEnabled(), applicant(applicationId));
        return new AdminInclusionView(current, revisions(applicationId),
            Set.of("ADMIN", "PK_ADMIN").contains(actor.role()), application.processOpen());
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
                    state, ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version, authored_by, change_origin)
                VALUES (:revisionId, :inclusionId, :revision, :state, :ciphertext, :iv,
                    :wrappedDek, :wrappedDekIv, :keyVersion, :actorId, 'FAMILY')
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
            return read(applicationId, true, applicant(applicationId));
        });
    }

    public AdminInclusionView correctDirectly(UUID applicationId, Map<String, Object> answers,
                                               String reason, long expectedVersion) {
        PrekinderActor actor = access.requireSuperAdmin();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("La corrección administrativa requiere motivo");
        }
        Set<String> editable = Set.of("backgroundSummary", "currentSupports", "relevantDocuments");
        Map<String, Object> requested = answers == null ? Map.of() : answers;
        if (requested.isEmpty() || requested.keySet().stream().anyMatch(key -> !editable.contains(key))) {
            throw new IllegalArgumentException("La corrección contiene campos no permitidos");
        }
        return transactions.execute(status -> {
            List<RecordState> records = jdbc.query("""
                SELECT record.inclusion_id, record.version, revision.ciphertext, revision.iv,
                       revision.wrapped_dek, revision.wrapped_dek_iv, revision.key_version
                  FROM inclusion_records record
                  JOIN LATERAL (
                      SELECT item.* FROM inclusion_record_revisions item
                       WHERE item.inclusion_id = record.inclusion_id
                       ORDER BY item.revision_number DESC LIMIT 1
                  ) revision ON true
                 WHERE record.application_id = :applicationId
                 FOR UPDATE OF record
                """, Map.of("applicationId", applicationId), (rs, row) -> new RecordState(
                    rs.getObject("inclusion_id", UUID.class), rs.getLong("version"),
                    new EncryptedPayload(rs.getString("ciphertext"), rs.getString("iv"),
                        rs.getString("wrapped_dek"), rs.getString("wrapped_dek_iv"), rs.getString("key_version"))));
            if (records.isEmpty()) throw PrekinderDomainException.conflict("INCLUSION_NOT_DECLARED",
                "El postulante no posee una declaración de inclusión para corregir");
            RecordState record = records.getFirst();
            if (record.version() != expectedVersion) {
                throw new VersionConflictException("La declaración de inclusión cambió; vuelve a cargarla");
            }
            Map<String, Object> corrected = decrypt(applicationId, record.payload());
            requested.forEach(corrected::put);
            UUID revisionId = UUID.randomUUID();
            Integer nextRevision = jdbc.queryForObject("""
                SELECT COALESCE(max(revision_number), 0) + 1
                  FROM inclusion_record_revisions WHERE inclusion_id = :id
                """, Map.of("id", record.inclusionId()), Integer.class);
            EncryptedPayload encrypted = encrypt(applicationId, corrected);
            EncryptedPayload encryptedReason = encryption.encrypt(reason.trim(), reasonAad(revisionId, applicationId));
            jdbc.update("""
                INSERT INTO inclusion_record_revisions(inclusion_revision_id, inclusion_id, revision_number,
                    state, ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version, authored_by,
                    change_origin, reason_ciphertext, reason_iv, reason_wrapped_dek, reason_wrapped_dek_iv,
                    reason_key_version)
                VALUES (:revisionId, :inclusionId, :revision, 'SUBMITTED', :ciphertext, :iv,
                    :wrappedDek, :wrappedDekIv, :keyVersion, :actorId, 'ADMIN_DIRECT_EDIT',
                    :reasonCiphertext, :reasonIv, :reasonWrappedDek, :reasonWrappedDekIv, :reasonKeyVersion)
                """, new MapSqlParameterSource().addValue("revisionId", revisionId)
                .addValue("inclusionId", record.inclusionId()).addValue("revision", nextRevision)
                .addValue("ciphertext", encrypted.ciphertext()).addValue("iv", encrypted.iv())
                .addValue("wrappedDek", encrypted.wrappedDek()).addValue("wrappedDekIv", encrypted.wrappedDekIv())
                .addValue("keyVersion", encrypted.keyVersion()).addValue("actorId", actor.id())
                .addValue("reasonCiphertext", encryptedReason.ciphertext()).addValue("reasonIv", encryptedReason.iv())
                .addValue("reasonWrappedDek", encryptedReason.wrappedDek())
                .addValue("reasonWrappedDekIv", encryptedReason.wrappedDekIv())
                .addValue("reasonKeyVersion", encryptedReason.keyVersion()));
            jdbc.update("UPDATE inclusion_records SET version = version + 1, updated_at = now() WHERE inclusion_id = :id",
                Map.of("id", record.inclusionId()));
            audit(actor.id(), "INCLUSION_ADMIN_DIRECT_CORRECTION", applicationId,
                Map.of("revision", nextRevision, "processOpen", applicationAccess(applicationId).processOpen()));
            return getForAdmin(applicationId);
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
        return read(applicationId, true, applicant(applicationId));
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

    private record CorrectionScope(List<String> allowedFields, String reason) {}

    private CorrectionScope loadCorrectionScope(UUID applicationId) {
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT allowed_fields, reason_ciphertext, reason_iv,
                   reason_wrapped_dek, reason_wrapped_dek_iv, reason_key_version
              FROM application_correction_requests
             WHERE application_id = :applicationId AND status = 'OPEN'
            """, Map.of("applicationId", applicationId), (rs, row) -> Map.of(
                "allowedFields", rs.getObject("allowed_fields"),
                "reasonCiphertext", rs.getString("reason_ciphertext"),
                "reasonIv", rs.getString("reason_iv"),
                "reasonWrappedDek", rs.getString("reason_wrapped_dek"),
                "reasonWrappedDekIv", rs.getString("reason_wrapped_dek_iv"),
                "reasonKeyVersion", rs.getString("reason_key_version")));
        if (rows.isEmpty()) return new CorrectionScope(List.of(), null);
        Map<String, Object> row = rows.getFirst();
        @SuppressWarnings("unchecked")
        List<String> allowedFields = (List<String>) row.get("allowedFields");
        List<String> inclusionFields = allowedFields.stream()
            .filter(f -> f.startsWith("inclusion."))
            .map(f -> f.substring("inclusion.".length()))
            .toList();
        String reason = decryptReason(applicationId,
            new EncryptedPayload(
                (String) row.get("reasonCiphertext"),
                (String) row.get("reasonIv"),
                (String) row.get("reasonWrappedDek"),
                (String) row.get("reasonWrappedDekIv"),
                (String) row.get("reasonKeyVersion")));
        return new CorrectionScope(inclusionFields, reason);
    }

    private String decryptReason(UUID applicationId, EncryptedPayload payload) {
        try {
            String aad = "prekinder|correction|application:" + applicationId;
            return mapper.readValue(encryption.decrypt(payload, aad), String.class);
        } catch (Exception e) {
            return null;
        }
    }

    private InclusionView read(UUID applicationId, boolean enabled, ApplicantSummary applicant) {
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
                CorrectionScope correction = loadCorrectionScope(applicationId);
                return new InclusionView(enabled, true, rs.getObject("inclusion_id", UUID.class),
                    rs.getString("consent_status"), rs.getBoolean("specific_interview_required"),
                    rs.getString("specific_interview_status"), declaredAt == null ? null : declaredAt.toInstant(),
                    rs.getLong("version"), revisionNumber, rs.getString("state"), declaration, applicant,
                    correction.allowedFields(), correction.reason());
            });
        return rows.isEmpty() ? new InclusionView(enabled, false, null, "PENDING", false, "NOT_REQUIRED",
            null, 0, null, null, Map.of(), applicant, List.of(), null) : rows.getFirst();
    }

    private ApplicationAccess assertOwner(UUID applicationId, PrekinderActor actor) {
        List<ApplicationAccess> rows = jdbc.query("""
            SELECT config.inclusion_enabled, application.payment_required, application.payment_status,
                   (process.status NOT IN ('CLOSED','ARCHIVED')
                    AND (process.ends_at IS NULL OR process.ends_at >= now())) AS process_open
              FROM applications application
              JOIN applicants applicant ON applicant.applicant_id = application.applicant_id
              JOIN families family ON family.family_id = applicant.family_id
              JOIN prekinder_process_configuration config ON config.process_id = application.process_id
              JOIN admission_processes process ON process.process_id = application.process_id
             WHERE application.application_id = :applicationId AND family.external_reference = :actorReference
            """, Map.of("applicationId", applicationId, "actorReference", actor.id().toString()),
            (rs, row) -> new ApplicationAccess(rs.getBoolean("inclusion_enabled"),
                rs.getBoolean("payment_required"), rs.getString("payment_status"), rs.getBoolean("process_open")));
        if (rows.isEmpty()) throw PrekinderDomainException.forbidden("APPLICATION_NOT_OWNED",
            "Postulación Prekínder no autorizada");
        return rows.getFirst();
    }

    private ApplicationAccess applicationAccess(UUID applicationId) {
        List<ApplicationAccess> rows = jdbc.query("""
            SELECT config.inclusion_enabled, application.payment_required, application.payment_status,
                   (process.status NOT IN ('CLOSED','ARCHIVED')
                    AND (process.ends_at IS NULL OR process.ends_at >= now())) AS process_open
              FROM applications application
              JOIN prekinder_process_configuration config ON config.process_id = application.process_id
              JOIN admission_processes process ON process.process_id = application.process_id
             WHERE application.application_id = :applicationId
            """, Map.of("applicationId", applicationId), (rs, row) -> new ApplicationAccess(
                rs.getBoolean("inclusion_enabled"), rs.getBoolean("payment_required"),
                rs.getString("payment_status"), rs.getBoolean("process_open")));
        if (rows.isEmpty()) throw PrekinderDomainException.conflict("APPLICATION_NOT_FOUND", "Postulación Prekínder no encontrada");
        return rows.getFirst();
    }

    private ApplicantSummary applicant(UUID applicationId) {
        return jdbc.query("""
            SELECT application.application_id, application.folio, applicant.applicant_id,
                   applicant.identity_ciphertext, applicant.identity_iv, applicant.identity_wrapped_dek,
                   applicant.identity_wrapped_dek_iv, applicant.identity_key_version,
                   process.name AS process_name, process.academic_year
              FROM applications application
              JOIN applicants applicant ON applicant.applicant_id = application.applicant_id
              JOIN admission_processes process ON process.process_id = application.process_id
             WHERE application.application_id = :applicationId
            """, Map.of("applicationId", applicationId), (rs, row) -> {
                UUID applicantId = rs.getObject("applicant_id", UUID.class);
                EncryptedPayload identityPayload = new EncryptedPayload(rs.getString("identity_ciphertext"),
                    rs.getString("identity_iv"), rs.getString("identity_wrapped_dek"),
                    rs.getString("identity_wrapped_dek_iv"), rs.getString("identity_key_version"));
                ApplicantIdentity identity = decryptIdentity(applicationId, applicantId, identityPayload);
                return new ApplicantSummary(applicationId,
                    java.util.stream.Stream.of(identity.firstName(), identity.paternalLastName(), identity.maternalLastName())
                        .filter(value -> value != null && !value.isBlank()).collect(java.util.stream.Collectors.joining(" ")),
                    maskRut(identity.rut()), rs.getString("process_name"), rs.getInt("academic_year"), rs.getString("folio"));
            }).stream().findFirst().orElseThrow(() -> PrekinderDomainException.conflict(
                "APPLICATION_NOT_FOUND", "Postulación Prekínder no encontrada"));
    }

    private List<RevisionView> revisions(UUID applicationId) {
        return jdbc.query("""
            SELECT revision.inclusion_revision_id, revision.revision_number, revision.state,
                   revision.change_origin, revision.created_at, revision.ciphertext, revision.iv,
                   revision.wrapped_dek, revision.wrapped_dek_iv, revision.key_version,
                   revision.reason_ciphertext, revision.reason_iv, revision.reason_wrapped_dek,
                   revision.reason_wrapped_dek_iv, revision.reason_key_version,
                   actor.actor_id, actor.display_name, actor.role_code
              FROM inclusion_records record
              JOIN inclusion_record_revisions revision ON revision.inclusion_id = record.inclusion_id
              JOIN actors actor ON actor.actor_id = revision.authored_by
             WHERE record.application_id = :applicationId
             ORDER BY revision.revision_number DESC
            """, Map.of("applicationId", applicationId), (rs, row) -> {
                UUID revisionId = rs.getObject("inclusion_revision_id", UUID.class);
                Map<String, Object> declaration = decrypt(applicationId, new EncryptedPayload(
                    rs.getString("ciphertext"), rs.getString("iv"), rs.getString("wrapped_dek"),
                    rs.getString("wrapped_dek_iv"), rs.getString("key_version")));
                String reason = null;
                if (rs.getString("reason_ciphertext") != null) {
                    reason = encryption.decrypt(new EncryptedPayload(rs.getString("reason_ciphertext"),
                        rs.getString("reason_iv"), rs.getString("reason_wrapped_dek"),
                        rs.getString("reason_wrapped_dek_iv"), rs.getString("reason_key_version")),
                        reasonAad(revisionId, applicationId));
                }
                Timestamp createdAt = rs.getTimestamp("created_at");
                return new RevisionView(revisionId, rs.getInt("revision_number"), rs.getString("state"),
                    rs.getString("change_origin"), rs.getObject("actor_id", UUID.class), rs.getString("display_name"),
                    rs.getString("role_code"), createdAt == null ? null : createdAt.toInstant(), reason, declaration);
            });
    }

    private ApplicantIdentity decryptIdentity(UUID applicationId, UUID applicantId, EncryptedPayload payload) {
        try {
            String identityAad = "prekinder|applicants|" + applicantId + "|application:" + applicationId + "|identity";
            return mapper.readValue(encryption.decrypt(payload, identityAad), ApplicantIdentity.class);
        } catch (Exception exception) {
            throw new IllegalStateException("La identidad cifrada de Prekínder no tiene un formato válido", exception);
        }
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
    private static String reasonAad(UUID revisionId, UUID applicationId) {
        return "prekinder|inclusion-reason|revision:" + revisionId + "|application:" + applicationId;
    }
    private static String maskRut(String rut) {
        if (rut == null || rut.isBlank()) return "No informado";
        String normalized = rut.replaceAll("[^0-9kK]", "");
        if (normalized.length() <= 4) return "****";
        return "****" + normalized.substring(normalized.length() - 4);
    }
    private record ApplicationAccess(boolean inclusionEnabled, boolean paymentRequired, String paymentStatus,
                                     boolean processOpen) {}
    private record RecordState(UUID inclusionId, long version, EncryptedPayload payload) {}
    private record ApplicantIdentity(String rut, String firstName, String paternalLastName,
                                     String maternalLastName, LocalDate birthDate, String familyEmail,
                                     String fatherEmail, String motherEmail) {}
    public record ApplicantSummary(UUID applicationId, String fullName, String maskedRut, String processName,
                                   int academicYear, String folio) {}
    public record RevisionView(UUID revisionId, int revisionNumber, String state, String changeOrigin,
                               UUID authorId, String authorName, String authorRole, Instant createdAt,
                               String reason, Map<String, Object> declaration) {}
    public record AdminInclusionView(InclusionView current, List<RevisionView> revisions,
                                     boolean canDirectEdit, boolean processOpen) {}
    public record InclusionView(boolean enabled, boolean declared, UUID inclusionId, String consentStatus,
        boolean specificInterviewRequired, String specificInterviewStatus, Instant declaredAt, long version,
        Integer revisionNumber, String revisionState, Map<String, Object> declaration,
        ApplicantSummary applicant,
        List<String> allowedFields, String correctionRequestReason) {}
}
