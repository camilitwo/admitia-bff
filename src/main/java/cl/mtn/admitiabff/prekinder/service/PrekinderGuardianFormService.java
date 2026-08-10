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

    public PrekinderGuardianFormService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
                                        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
                                        PrekinderAccessService access,
                                        EnvelopeEncryptionService encryption,
                                        ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.encryption = encryption;
        this.mapper = mapper;
    }

    public Map<String, Object> get(UUID applicationId) {
        PrekinderActor actor = access.requireActor();
        assertOwned(applicationId, actor);
        List<Map<String, Object>> forms = jdbc.query("""
            SELECT form_id, ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version,
                   submitted, submitted_at, version, created_at, updated_at
              FROM prekinder_complementary_forms WHERE application_id = :applicationId
            """, Map.of("applicationId", applicationId), (rs, row) -> {
                Map<String, Object> data = decrypt(applicationId, new EncryptedPayload(rs.getString("ciphertext"), rs.getString("iv"),
                    rs.getString("wrapped_dek"), rs.getString("wrapped_dek_iv"), rs.getString("key_version")));
                data.put("id", rs.getObject("form_id", UUID.class));
                data.put("applicationId", applicationId);
                data.put("isSubmitted", rs.getBoolean("submitted"));
                data.put("submittedAt", instant(rs.getTimestamp("submitted_at")));
                data.put("version", rs.getLong("version"));
                data.put("createdAt", instant(rs.getTimestamp("created_at")));
                data.put("updatedAt", instant(rs.getTimestamp("updated_at")));
                return data;
            });
        if (forms.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Formulario complementario no encontrado");
        return Map.of("success", true, "data", forms.get(0));
    }

    public Map<String, Object> save(UUID applicationId, Map<String, Object> payload) {
        PrekinderActor actor = access.requireActor();
        return transactions.execute(status -> {
            ApplicationState application = assertOwned(applicationId, actor);
            if (application.paymentRequired() && !"PAID".equals(application.paymentStatus())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Debe pagar la postulación Prekínder antes de completar el formulario complementario");
            }
            ExistingForm existing = existing(applicationId);
            if (existing != null && existing.submitted()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "El formulario complementario ya fue enviado");
            }
            boolean submitted = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("isSubmitted", false)));
            UUID formId = existing == null ? UUID.randomUUID() : existing.formId();
            EncryptedPayload encrypted = encrypt(applicationId, payload);
            jdbc.update("""
                INSERT INTO prekinder_complementary_forms(form_id, application_id, ciphertext, iv, wrapped_dek,
                    wrapped_dek_iv, key_version, submitted, submitted_at)
                VALUES (:formId, :applicationId, :ciphertext, :iv, :wrappedDek, :wrappedDekIv, :keyVersion,
                    :submitted, :submittedAt)
                ON CONFLICT (application_id) DO UPDATE SET ciphertext = EXCLUDED.ciphertext, iv = EXCLUDED.iv,
                    wrapped_dek = EXCLUDED.wrapped_dek, wrapped_dek_iv = EXCLUDED.wrapped_dek_iv,
                    key_version = EXCLUDED.key_version, submitted = EXCLUDED.submitted,
                    submitted_at = EXCLUDED.submitted_at, version = prekinder_complementary_forms.version + 1,
                    updated_at = now()
                """, encryptedValues(formId, applicationId, encrypted, submitted));
            return get(applicationId);
        });
    }

    private ApplicationState assertOwned(UUID applicationId, PrekinderActor actor) {
        List<ApplicationState> rows = jdbc.query("""
            SELECT a.payment_required, a.payment_status
              FROM applications a JOIN applicants ap ON ap.applicant_id = a.applicant_id
              JOIN families f ON f.family_id = ap.family_id
             WHERE a.application_id = :applicationId AND f.external_reference = :actorReference
            """, Map.of("applicationId", applicationId, "actorReference", actor.id().toString()),
            (rs, row) -> new ApplicationState(rs.getBoolean("payment_required"), rs.getString("payment_status")));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Postulación Prekínder no encontrada");
        return rows.get(0);
    }

    private ExistingForm existing(UUID applicationId) {
        List<ExistingForm> rows = jdbc.query("SELECT form_id, submitted FROM prekinder_complementary_forms WHERE application_id = :id FOR UPDATE",
            Map.of("id", applicationId), (rs, row) -> new ExistingForm(rs.getObject("form_id", UUID.class), rs.getBoolean("submitted")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private EncryptedPayload encrypt(UUID applicationId, Map<String, Object> payload) {
        try { return encryption.encrypt(mapper.writeValueAsString(payload), aad(applicationId)); }
        catch (Exception exception) { throw new IllegalArgumentException("El formulario complementario no tiene un formato válido", exception); }
    }

    private Map<String, Object> decrypt(UUID applicationId, EncryptedPayload payload) {
        try {
            return new LinkedHashMap<>(mapper.readValue(encryption.decrypt(payload, aad(applicationId)), new TypeReference<>() {}));
        } catch (Exception exception) { throw new IllegalStateException("El formulario complementario cifrado no es válido", exception); }
    }

    private MapSqlParameterSource encryptedValues(UUID formId, UUID applicationId, EncryptedPayload value,
                                                   boolean submitted) {
        return new MapSqlParameterSource().addValue("formId", formId).addValue("applicationId", applicationId)
            .addValue("ciphertext", value.ciphertext()).addValue("iv", value.iv())
            .addValue("wrappedDek", value.wrappedDek()).addValue("wrappedDekIv", value.wrappedDekIv())
            .addValue("keyVersion", value.keyVersion()).addValue("submitted", submitted)
            .addValue("submittedAt", submitted ? Timestamp.from(Instant.now()) : null);
    }

    private static String aad(UUID applicationId) { return "prekinder|complementary-form|application:" + applicationId; }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private record ApplicationState(boolean paymentRequired, String paymentStatus) { }
    private record ExistingForm(UUID formId, boolean submitted) { }
}
