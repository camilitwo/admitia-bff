package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderGuardianFormService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final EnvelopeEncryptionService encryption;
    private final ObjectMapper mapper;
    private final PrekinderApplicationStateService applicationStates;

    public PrekinderGuardianFormService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
                                        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
                                        PrekinderAccessService access,
                                        EnvelopeEncryptionService encryption,
                                        ObjectMapper mapper,
                                        PrekinderApplicationStateService applicationStates) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.encryption = encryption;
        this.mapper = mapper;
        this.applicationStates = applicationStates;
    }

    public Map<String, Object> get(UUID applicationId) {
        PrekinderActor actor = access.requireActor();
        ApplicationState application = assertOwned(applicationId, actor);
        List<Map<String, Object>> forms = jdbc.query("""
            SELECT form_id, application_id, template_version_id, ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version,
                   submitted, submitted_at, version, created_at, updated_at
              FROM prekinder_complementary_forms WHERE family_id = :familyId AND process_id = :processId
            """, Map.of("familyId", application.familyId(), "processId", application.processId()), (rs, row) -> {
                UUID originalApplicationId = rs.getObject("application_id", UUID.class);
                EncryptedPayload encrypted = new EncryptedPayload(rs.getString("ciphertext"), rs.getString("iv"),
                    rs.getString("wrapped_dek"), rs.getString("wrapped_dek_iv"), rs.getString("key_version"));
                Map<String, Object> data = decryptFamilyOrLegacy(application, originalApplicationId, encrypted);
                data.put("id", rs.getObject("form_id", UUID.class));
                data.put("applicationId", applicationId);
                data.put("familyId", application.familyId());
                data.put("processKey", application.processId().toString());
                data.put("processOpen", application.processOpen());
                data.put("templateVersionId", rs.getObject("template_version_id", UUID.class));
                data.put("isSubmitted", rs.getBoolean("submitted"));
                data.put("submittedAt", instant(rs.getTimestamp("submitted_at")));
                data.put("version", rs.getLong("version"));
                data.put("createdAt", instant(rs.getTimestamp("created_at")));
                data.put("updatedAt", instant(rs.getTimestamp("updated_at")));
                data.put("applicants", familyApplicants(application));
                return data;
            });
        if (forms.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Formulario complementario no encontrado");
        return Map.of("success", true, "data", forms.get(0));
    }

    public Map<String, Object> save(UUID applicationId, Map<String, Object> payload) {
        PrekinderActor actor = access.requireActor();
        return transactions.execute(status -> {
            ApplicationState application = assertOwned(applicationId, actor);
            if (!application.processOpen()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "El proceso Prekínder está cerrado para edición");
            }
            Integer eligible = jdbc.queryForObject("""
                SELECT count(*) FROM applications candidate
                  JOIN applicants applicant ON applicant.applicant_id = candidate.applicant_id
                 WHERE applicant.family_id = :familyId AND candidate.process_id = :processId
                   AND (candidate.payment_required = false OR candidate.payment_status = 'PAID')
                """, Map.of("familyId", application.familyId(), "processId", application.processId()), Integer.class);
            if (eligible == null || eligible == 0) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Debe pagar la postulación Prekínder antes de completar el formulario complementario");
            }
            ExistingForm existing = existing(application);
            boolean submitted = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("isSubmitted", false)));
            UUID formId = existing == null ? UUID.randomUUID() : existing.formId();
            UUID templateVersionId = existing == null ? publishedTemplateVersion(applicationId) : null;
            EncryptedPayload encrypted = encrypt(application, payload);
            jdbc.update("""
                INSERT INTO prekinder_complementary_forms(form_id, application_id, family_id, process_id, template_version_id, ciphertext, iv, wrapped_dek,
                    wrapped_dek_iv, key_version, submitted, submitted_at)
                VALUES (:formId, :applicationId, :familyId, :processId, :templateVersionId, :ciphertext, :iv, :wrappedDek, :wrappedDekIv, :keyVersion,
                    :submitted, :submittedAt)
                ON CONFLICT (family_id, process_id) DO UPDATE SET ciphertext = EXCLUDED.ciphertext, iv = EXCLUDED.iv,
                    wrapped_dek = EXCLUDED.wrapped_dek, wrapped_dek_iv = EXCLUDED.wrapped_dek_iv,
                    key_version = EXCLUDED.key_version, submitted = prekinder_complementary_forms.submitted OR EXCLUDED.submitted,
                    submitted_at = COALESCE(prekinder_complementary_forms.submitted_at, EXCLUDED.submitted_at),
                    version = prekinder_complementary_forms.version + 1,
                    updated_at = now()
                """, encryptedValues(formId, applicationId, application, encrypted, submitted)
                    .addValue("templateVersionId", templateVersionId));
            if (submitted) jdbc.update("""
                UPDATE application_correction_requests SET status = 'COMPLETED', completed_at = now()
                 WHERE application_id = :id AND status = 'OPEN'
                """, Map.of("id", applicationId));
            applicationStates.refresh(applicationId, actor.id());
            return get(applicationId);
        });
    }

    private ApplicationState assertOwned(UUID applicationId, PrekinderActor actor) {
        List<ApplicationState> rows = jdbc.query("""
            SELECT a.payment_required, a.payment_status, f.family_id, a.process_id,
                   (process.status NOT IN ('CLOSED', 'ARCHIVED')
                    AND (process.ends_at IS NULL OR process.ends_at >= now())) AS process_open
              FROM applications a JOIN applicants ap ON ap.applicant_id = a.applicant_id
              JOIN families f ON f.family_id = ap.family_id
              JOIN admission_processes process ON process.process_id = a.process_id
             WHERE a.application_id = :applicationId AND f.external_reference = :actorReference
            """, Map.of("applicationId", applicationId, "actorReference", actor.id().toString()),
            (rs, row) -> new ApplicationState(rs.getBoolean("payment_required"), rs.getString("payment_status"),
                rs.getObject("family_id", UUID.class), rs.getObject("process_id", UUID.class), rs.getBoolean("process_open")));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Postulación Prekínder no encontrada");
        return rows.get(0);
    }

    private ExistingForm existing(ApplicationState application) {
        List<ExistingForm> rows = jdbc.query("SELECT form_id, submitted FROM prekinder_complementary_forms WHERE family_id = :familyId AND process_id = :processId FOR UPDATE",
            Map.of("familyId", application.familyId(), "processId", application.processId()),
            (rs, row) -> new ExistingForm(rs.getObject("form_id", UUID.class), rs.getBoolean("submitted")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UUID publishedTemplateVersion(UUID applicationId) {
        List<UUID> versions = jdbc.queryForList("""
            SELECT version.template_version_id
              FROM applications application
              JOIN form_templates template ON template.process_id = application.process_id
              JOIN form_template_versions version ON version.template_id = template.template_id
             WHERE application.application_id = :id AND template.code = 'COMPLEMENTARY_FORM'
               AND version.status = 'PUBLISHED'
             ORDER BY version.version DESC LIMIT 1
            """, Map.of("id", applicationId), UUID.class);
        if (versions.isEmpty()) throw PrekinderDomainException.conflict("QUESTIONNAIRE_NOT_PUBLISHED",
            "El cuestionario Prekínder aún no está publicado");
        return versions.getFirst();
    }

    private void validateCorrectionScope(UUID applicationId, ApplicationState application, Map<String, Object> next) {
        List<String> allowed = jdbc.query("""
            SELECT field FROM application_correction_requests request,
                 LATERAL jsonb_array_elements_text(request.allowed_fields) field
             WHERE request.application_id = :id AND request.status = 'OPEN'
            """, Map.of("id", applicationId), (rs, row) -> rs.getString(1));
        if (allowed.isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El formulario complementario ya fue enviado");
        Map<String, Object> current = jdbc.queryForObject("""
            SELECT application_id, ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version
              FROM prekinder_complementary_forms WHERE family_id = :familyId AND process_id = :processId
            """, Map.of("familyId", application.familyId(), "processId", application.processId()),
            (rs, row) -> decryptFamilyOrLegacy(application, rs.getObject("application_id", UUID.class), new EncryptedPayload(
                rs.getString("ciphertext"), rs.getString("iv"), rs.getString("wrapped_dek"),
                rs.getString("wrapped_dek_iv"), rs.getString("key_version"))));
        java.util.Set<String> allowedSet = new java.util.HashSet<>(allowed);
        java.util.Set<String> keys = new java.util.HashSet<>(current.keySet());
        keys.addAll(next.keySet());
        keys.remove("isSubmitted");
        for (String key : keys) {
            if (!java.util.Objects.equals(mapper.valueToTree(current.get(key)), mapper.valueToTree(next.get(key)))
                && !allowedSet.contains(key)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "El campo " + key + " no fue habilitado para corrección");
            }
        }
    }

    private EncryptedPayload encrypt(ApplicationState application, Map<String, Object> payload) {
        try { return encryption.encrypt(mapper.writeValueAsString(payload), aad(application)); }
        catch (Exception exception) { throw new IllegalArgumentException("El formulario complementario no tiene un formato válido", exception); }
    }

    private Map<String, Object> decrypt(UUID applicationId, EncryptedPayload payload) {
        try {
            return new LinkedHashMap<>(mapper.readValue(encryption.decrypt(payload, aad(applicationId)), new TypeReference<>() {}));
        } catch (Exception exception) { throw new IllegalStateException("El formulario complementario cifrado no es válido", exception); }
    }

    private Map<String, Object> decryptFamilyOrLegacy(ApplicationState application, UUID legacyApplicationId, EncryptedPayload payload) {
        try {
            return new LinkedHashMap<>(mapper.readValue(encryption.decrypt(payload, aad(application)), new TypeReference<>() {}));
        } catch (Exception ignored) {
            return decrypt(legacyApplicationId, payload);
        }
    }

    private MapSqlParameterSource encryptedValues(UUID formId, UUID applicationId, ApplicationState application, EncryptedPayload value,
                                                   boolean submitted) {
        return new MapSqlParameterSource().addValue("formId", formId).addValue("applicationId", applicationId)
            .addValue("familyId", application.familyId()).addValue("processId", application.processId())
            .addValue("ciphertext", value.ciphertext()).addValue("iv", value.iv())
            .addValue("wrappedDek", value.wrappedDek()).addValue("wrappedDekIv", value.wrappedDekIv())
            .addValue("keyVersion", value.keyVersion()).addValue("submitted", submitted)
            .addValue("submittedAt", submitted ? Timestamp.from(Instant.now()) : null);
    }

    private static String aad(UUID applicationId) { return "prekinder|complementary-form|application:" + applicationId; }
    private static String aad(ApplicationState application) {
        return "prekinder|complementary-form|family:" + application.familyId() + "|process:" + application.processId();
    }

    private List<Map<String, Object>> familyApplicants(ApplicationState application) {
        return jdbc.query("""
            SELECT candidate.application_id, applicant.identity_ciphertext
              FROM applications candidate JOIN applicants applicant ON applicant.applicant_id = candidate.applicant_id
             WHERE applicant.family_id = :familyId AND candidate.process_id = :processId
             ORDER BY candidate.created_at
            """, Map.of("familyId", application.familyId(), "processId", application.processId()),
            (rs, row) -> Map.of("applicationId", rs.getObject("application_id", UUID.class)));
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private record ApplicationState(boolean paymentRequired, String paymentStatus, UUID familyId, UUID processId,
                                    boolean processOpen) { }
    private record ExistingForm(UUID formId, boolean submitted) { }
}
