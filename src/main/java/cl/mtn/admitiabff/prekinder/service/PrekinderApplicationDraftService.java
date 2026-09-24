package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
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
public class PrekinderApplicationDraftService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final EnvelopeEncryptionService encryption;
    private final ObjectMapper mapper;

    public PrekinderApplicationDraftService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
        PrekinderAccessService access, EnvelopeEncryptionService encryption, ObjectMapper mapper) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.access = access;
        this.encryption = encryption; this.mapper = mapper;
    }

    public DraftView get(UUID processId) {
        PrekinderActor actor = access.requireActor();
        List<DraftView> rows = jdbc.query("""
            SELECT draft_id, current_section, ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version, version
              FROM guardian_application_drafts WHERE process_id = :processId AND actor_id = :actorId
            """, Map.of("processId", processId, "actorId", actor.id()), (rs, row) -> {
                UUID draftId = rs.getObject("draft_id", UUID.class);
                Map<String, Object> data = decrypt(draftId, new EncryptedPayload(rs.getString("ciphertext"),
                    rs.getString("iv"), rs.getString("wrapped_dek"), rs.getString("wrapped_dek_iv"),
                    rs.getString("key_version")));
                return new DraftView(draftId, processId, rs.getInt("current_section"), data, rs.getLong("version"));
            });
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public DraftView save(UUID processId, int currentSection, Map<String, Object> data, Long expectedVersion) {
        PrekinderActor actor = access.requireActor();
        if (data == null || data.isEmpty()) throw new IllegalArgumentException("El borrador está vacío");
        return transactions.execute(status -> {
            DraftView current = get(processId);
            UUID draftId = current == null ? UUID.randomUUID() : current.draftId();
            if (current != null && expectedVersion != null && current.version() != expectedVersion) {
                throw new VersionConflictException("El borrador cambió en otra sesión");
            }
            EncryptedPayload encrypted = encrypt(draftId, data);
            jdbc.update("""
                INSERT INTO guardian_application_drafts(draft_id, process_id, actor_id, current_section,
                    ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version)
                VALUES (:id, :processId, :actorId, :section, :ciphertext, :iv, :wrappedDek, :wrappedDekIv, :keyVersion)
                ON CONFLICT (process_id, actor_id) DO UPDATE SET current_section = EXCLUDED.current_section,
                    ciphertext = EXCLUDED.ciphertext, iv = EXCLUDED.iv, wrapped_dek = EXCLUDED.wrapped_dek,
                    wrapped_dek_iv = EXCLUDED.wrapped_dek_iv, key_version = EXCLUDED.key_version,
                    version = guardian_application_drafts.version + 1, updated_at = now()
                """, new MapSqlParameterSource().addValue("id", draftId).addValue("processId", processId)
                .addValue("actorId", actor.id()).addValue("section", currentSection)
                .addValue("ciphertext", encrypted.ciphertext()).addValue("iv", encrypted.iv())
                .addValue("wrappedDek", encrypted.wrappedDek()).addValue("wrappedDekIv", encrypted.wrappedDekIv())
                .addValue("keyVersion", encrypted.keyVersion()));
            return get(processId);
        });
    }

    public void delete(UUID processId) {
        PrekinderActor actor = access.requireActor();
        jdbc.update("DELETE FROM guardian_application_drafts WHERE process_id = :processId AND actor_id = :actorId",
            Map.of("processId", processId, "actorId", actor.id()));
    }

    private EncryptedPayload encrypt(UUID draftId, Map<String, Object> data) {
        try { return encryption.encrypt(mapper.writeValueAsString(data), aad(draftId)); }
        catch (Exception exception) { throw new IllegalArgumentException("El borrador no tiene un formato válido", exception); }
    }
    private Map<String, Object> decrypt(UUID draftId, EncryptedPayload payload) {
        try { return mapper.readValue(encryption.decrypt(payload, aad(draftId)), new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("El borrador cifrado no es válido", exception); }
    }
    private static String aad(UUID draftId) { return "prekinder|guardian-draft|" + draftId; }
    public record DraftView(UUID draftId, UUID processId, int currentSection, Map<String, Object> data, long version) {}
}
