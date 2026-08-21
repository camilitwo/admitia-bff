package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderOfferService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    public PrekinderOfferService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager, PrekinderAccessService access) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.access = access;
    }

    public List<OfferView> mine() {
        PrekinderActor actor = access.requireActor();
        return jdbc.query("""
            SELECT offer.offer_id, offer.application_id, offer.status, offer.expires_at, offer.version,
                   process.name AS process_name, process.academic_year
              FROM offers offer JOIN applications application ON application.application_id = offer.application_id
              JOIN applicants applicant ON applicant.applicant_id = application.applicant_id
              JOIN families family ON family.family_id = applicant.family_id
              JOIN admission_processes process ON process.process_id = application.process_id
             WHERE family.external_reference = :actorReference ORDER BY offer.created_at DESC
            """, Map.of("actorReference", actor.id().toString()), (rs, row) -> new OfferView(
                rs.getObject("offer_id", UUID.class), rs.getObject("application_id", UUID.class),
                rs.getString("status"), timestamp(rs.getTimestamp("expires_at")), rs.getLong("version"),
                rs.getString("process_name"), rs.getInt("academic_year")));
    }

    public OfferView respond(UUID offerId, String response, long expectedVersion) {
        PrekinderActor actor = access.requireActor();
        String next = switch (response == null ? "" : response.trim().toUpperCase()) {
            case "ACCEPTED" -> "ACCEPTED";
            case "DECLINED" -> "DECLINED";
            default -> throw new IllegalArgumentException("Respuesta de oferta inválida");
        };
        return transactions.execute(status -> {
            int updated = jdbc.update("""
                UPDATE offers offer SET status = :next, version = version + 1, updated_at = now()
                 FROM applications application, applicants applicant, families family
                 WHERE offer.offer_id = :id AND offer.version = :version AND offer.status = 'OFFERED'
                   AND offer.expires_at > now() AND application.application_id = offer.application_id
                   AND applicant.applicant_id = application.applicant_id AND family.family_id = applicant.family_id
                   AND family.external_reference = :actorReference
                """, Map.of("id", offerId, "version", expectedVersion, "next", next,
                    "actorReference", actor.id().toString()));
            if (updated != 1) throw new VersionConflictException("La oferta venció, cambió o no pertenece a esta familia");
            jdbc.update("""
                INSERT INTO offer_status_history(offer_history_id, offer_id, from_status, to_status, actor_id)
                VALUES (:id, :offerId, 'OFFERED', :next, :actorId)
                """, Map.of("id", UUID.randomUUID(), "offerId", offerId, "next", next, "actorId", actor.id()));
            jdbc.update("UPDATE applications SET status = :status, version = version + 1, updated_at = now() WHERE application_id = (SELECT application_id FROM offers WHERE offer_id = :id)",
                Map.of("id", offerId, "status", "ACCEPTED".equals(next) ? "OFFER_ACCEPTED" : "DECLINED"));
            return mine().stream().filter(value -> value.offerId().equals(offerId)).findFirst().orElseThrow();
        });
    }

    @Scheduled(cron = "${app.prekinder.offers.expiry-cron:0 */10 * * * *}")
    public void expire() {
        List<UUID> expired = jdbc.queryForList("SELECT offer_id FROM offers WHERE status = 'OFFERED' AND expires_at <= now()", Map.of(), UUID.class);
        for (UUID offerId : expired) transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("UPDATE offers SET status = 'EXPIRED', version = version + 1, updated_at = now() WHERE offer_id = :id AND status = 'OFFERED'",
                Map.of("id", offerId));
            if (updated == 1) jdbc.update("""
                INSERT INTO offer_status_history(offer_history_id, offer_id, from_status, to_status, reason_code)
                VALUES (:id, :offerId, 'OFFERED', 'EXPIRED', 'DEADLINE_REACHED')
                """, Map.of("id", UUID.randomUUID(), "offerId", offerId));
        });
    }
    private static Instant timestamp(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }
    public record OfferView(UUID offerId, UUID applicationId, String status, Instant expiresAt,
        long version, String processName, int academicYear) {}
}
