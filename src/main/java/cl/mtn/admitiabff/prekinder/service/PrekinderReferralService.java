package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.domain.PrekinderPolicyCodes;
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
public class PrekinderReferralService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final EnvelopeEncryptionService encryption;

    public PrekinderReferralService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
        PrekinderAccessService access, EnvelopeEncryptionService encryption) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager);
        this.access = access; this.encryption = encryption;
    }

    public ReferralView suggest(UUID applicationId, UUID sourceReportId, String targetType, String rationale) {
        PrekinderActor actor = access.requireEvaluator();
        String target = normalizeTarget(targetType);
        if (rationale == null || rationale.isBlank()) throw new IllegalArgumentException("La derivación requiere fundamento");
        return transactions.execute(status -> {
            Long accessCount = jdbc.queryForObject("""
                SELECT count(*) FROM evaluator_reports
                 WHERE report_id = :reportId AND application_id = :applicationId
                   AND (evaluator_id = :actorId OR :admin)
                """, Map.of("reportId", sourceReportId, "applicationId", applicationId,
                "actorId", actor.id(), "admin", List.of("ADMIN", "COORDINATOR", "PK_ADMIN", "PK_COORDINATOR").contains(actor.role())), Long.class);
            if (accessCount == null || accessCount == 0)
                throw PrekinderDomainException.forbidden("REFERRAL_SOURCE_FORBIDDEN", "El informe no pertenece al profesional");
            UUID referralId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO referrals(referral_id, application_id, source_type, source_id, target_type, status)
                VALUES (:id, :applicationId, 'EVALUATOR_REPORT', :sourceId, :target, 'SUGGESTED')
                """, Map.of("id", referralId, "applicationId", applicationId,
                "sourceId", sourceReportId, "target", target));
            revision(referralId, 1, "SUGGESTED", rationale, actor.id());
            audit(actor.id(), "REFERRAL_SUGGESTED", referralId, target);
            return view(referralId);
        });
    }

    public ReferralView review(UUID referralId, String decision, String rationale, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        String next = decision == null ? "" : decision.trim().toUpperCase();
        if (!List.of("APPROVED", "REJECTED", "REQUIRES_INFORMATION").contains(next))
            throw new IllegalArgumentException("Decisión de derivación inválida");
        if (rationale == null || rationale.isBlank()) throw new IllegalArgumentException("La revisión requiere fundamento");
        return transactions.execute(status -> {
            int updated = jdbc.update("""
                UPDATE referrals SET status = :status, version = version + 1, updated_at = now()
                 WHERE referral_id = :id AND version = :version
                   AND status IN ('SUGGESTED','UNDER_REVIEW','REQUIRES_INFORMATION')
                """, Map.of("id", referralId, "status", next, "version", expectedVersion));
            if (updated != 1) throw new VersionConflictException("La derivación cambió");
            revision(referralId, (int) expectedVersion + 2, next, rationale, actor.id());
            if ("APPROVED".equals(next)) {
                jdbc.update("""
                    INSERT INTO support_records(support_id, application_id, kind, status)
                    SELECT gen_random_uuid(), application_id, target_type, 'ASSIGNED'
                      FROM referrals WHERE referral_id = :id
                     AND NOT EXISTS (SELECT 1 FROM support_records support
                                      WHERE support.application_id = referrals.application_id
                                        AND support.kind = referrals.target_type
                                        AND support.status NOT IN ('CANCELLED','COMPLETED'))
                    """, Map.of("id", referralId));
            }
            audit(actor.id(), "REFERRAL_" + next, referralId, view(referralId).targetType());
            return view(referralId);
        });
    }

    public List<ReferralView> list(UUID applicationId) {
        access.requireAdmin();
        return jdbc.query("""
            SELECT referral_id, application_id, source_id, target_type, status,
                   assigned_actor_id, version, created_at, updated_at
              FROM referrals WHERE application_id = :id ORDER BY created_at
            """, Map.of("id", applicationId), (rs, row) -> map(rs));
    }

    private ReferralView view(UUID referralId) {
        return jdbc.queryForObject("""
            SELECT referral_id, application_id, source_id, target_type, status,
                   assigned_actor_id, version, created_at, updated_at
              FROM referrals WHERE referral_id = :id
            """, Map.of("id", referralId), (rs, row) -> map(rs));
    }

    private static ReferralView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReferralView(rs.getObject("referral_id", UUID.class), rs.getObject("application_id", UUID.class),
            rs.getObject("source_id", UUID.class), rs.getString("target_type"), rs.getString("status"),
            rs.getObject("assigned_actor_id", UUID.class), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private void revision(UUID referralId, int revision, String state, String rationale, UUID actorId) {
        EncryptedPayload value = encryption.encrypt(rationale.trim(),
            "prekinder|referral|" + referralId + "|revision:" + revision);
        jdbc.update("""
            INSERT INTO referral_revisions(referral_revision_id, referral_id, revision_number, status,
                rationale_ciphertext, rationale_iv, rationale_wrapped_dek, rationale_wrapped_dek_iv,
                rationale_key_version, actor_id)
            VALUES (:id, :referralId, :revision, :status, :ciphertext, :iv, :wrappedDek,
                :wrappedDekIv, :keyVersion, :actorId)
            """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("referralId", referralId)
            .addValue("revision", revision).addValue("status", state).addValue("actorId", actorId)
            .addValue("ciphertext", value.ciphertext()).addValue("iv", value.iv())
            .addValue("wrappedDek", value.wrappedDek()).addValue("wrappedDekIv", value.wrappedDekIv())
            .addValue("keyVersion", value.keyVersion()));
    }

    private void audit(UUID actorId, String action, UUID referralId, String target) {
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result, metadata)
            VALUES (:id, :actorId, :action, 'REFERRAL', :referralId, 'SUCCESS',
                jsonb_build_object('target', :target))
            """, Map.of("id", UUID.randomUUID(), "actorId", actorId, "action", action,
            "referralId", referralId, "target", target));
    }

    private static String normalizeTarget(String value) {
        String target = value == null ? "" : value.trim().toUpperCase();
        if (!PrekinderPolicyCodes.CONDITIONAL_INSTRUMENTS.contains(target))
            throw new IllegalArgumentException("La derivación debe ser LEARNING_SUPPORT o DAP");
        return target;
    }

    public record ReferralView(UUID referralId, UUID applicationId, UUID sourceReportId,
        String targetType, String status, UUID assignedActorId, long version,
        java.time.Instant createdAt, java.time.Instant updatedAt) {}
}
