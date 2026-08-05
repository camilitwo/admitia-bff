package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
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
public class PrekinderFieldService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final EnvelopeEncryptionService encryption;

    public PrekinderFieldService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
        PrekinderAccessService access, EnvelopeEncryptionService encryption) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.access = access; this.encryption = encryption;
    }

    public FieldResult update(UUID evaluationId, String fieldCode, long expectedVersion, UUID operationId, String content) {
        var actor = access.requireActor();
        return transactions.execute(status -> {
            var prior = jdbc.query("""
                SELECT result_reference FROM processed_operations WHERE operation_id=:operationId
                """, Map.of("operationId", operationId), (rs, row) -> rs.getObject(1, UUID.class));
            if (!prior.isEmpty()) return current(evaluationId, fieldCode, true);

            UUID fieldId = UUID.randomUUID();
            long nextVersion = expectedVersion + 1;
            var encrypted = encryption.encrypt(content,
                "prekinder|evaluation_fields|" + evaluationId + "|" + fieldCode + "|version:" + nextVersion);
            var params = new MapSqlParameterSource()
                .addValue("fieldId", fieldId).addValue("evaluationId", evaluationId).addValue("fieldCode", fieldCode)
                .addValue("ciphertext", encrypted.ciphertext()).addValue("iv", encrypted.iv())
                .addValue("wrappedDek", encrypted.wrappedDek()).addValue("wrappedDekIv", encrypted.wrappedDekIv())
                .addValue("keyVersion", encrypted.keyVersion()).addValue("actorId", actor.id())
                .addValue("expectedVersion", expectedVersion);
            var versions = jdbc.query("""
                INSERT INTO evaluation_fields(field_id, evaluation_id, field_code, ciphertext, iv, wrapped_dek,
                  wrapped_dek_iv, key_version, version, updated_by)
                SELECT :fieldId, :evaluationId, :fieldCode, :ciphertext, :iv, :wrappedDek,
                  :wrappedDekIv, :keyVersion, 1, :actorId
                WHERE :expectedVersion = 0
                ON CONFLICT (evaluation_id, field_code) DO UPDATE SET
                  ciphertext=EXCLUDED.ciphertext, iv=EXCLUDED.iv, wrapped_dek=EXCLUDED.wrapped_dek,
                  wrapped_dek_iv=EXCLUDED.wrapped_dek_iv, key_version=EXCLUDED.key_version,
                  version=evaluation_fields.version + 1, updated_by=EXCLUDED.updated_by, updated_at=now()
                WHERE evaluation_fields.version=:expectedVersion
                RETURNING field_id, version
                """, params, (rs, row) -> new FieldResult(rs.getObject("field_id", UUID.class), evaluationId,
                    fieldCode, rs.getLong("version"), false));
            if (versions.isEmpty()) throw new VersionConflictException("El campo cambió");
            FieldResult result = versions.getFirst();
            jdbc.update("""
                INSERT INTO processed_operations(operation_id, actor_id, operation_type, result_reference)
                VALUES (:operationId, :actorId, 'FIELD_UPDATE', :fieldId)
                """, Map.of("operationId", operationId, "actorId", actor.id(), "fieldId", result.fieldId()));
            return result;
        });
    }

    private FieldResult current(UUID evaluationId, String fieldCode, boolean duplicate) {
        return jdbc.queryForObject("""
            SELECT field_id, version FROM evaluation_fields WHERE evaluation_id=:id AND field_code=:field
            """, Map.of("id", evaluationId, "field", fieldCode), (rs, row) -> new FieldResult(
                rs.getObject("field_id", UUID.class), evaluationId, fieldCode, rs.getLong("version"), duplicate));
    }

    public record FieldResult(UUID fieldId, UUID evaluationId, String fieldCode, long version, boolean duplicate) {}
}
