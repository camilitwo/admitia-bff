package cl.mtn.admitiabff.service.reminder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class AssessmentStartReminderRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    @Autowired
    AssessmentStartReminderRepository(@Qualifier("legacyDataSource") DataSource dataSource,
            @Qualifier("legacyTransactionManager") PlatformTransactionManager transactionManager) {
        this(new NamedParameterJdbcTemplate(dataSource), transactionManager);
    }

    AssessmentStartReminderRepository(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    boolean isSlotMaterialized(Instant slot) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM assessment_start_reminder_runs WHERE scheduled_slot = :slot
            """, Map.of("slot", dbTimestamp(slot)), Integer.class);
        return count != null && count > 0;
    }

    void insertDelivery(long applicationId, String recipient, Instant slot, String status, String reason) {
        jdbc.update("""
            INSERT INTO assessment_start_reminder_deliveries(application_id, scheduled_slot,
                recipient, status, next_attempt_at, last_error)
            VALUES (:applicationId, :slot, :recipient, :status,
                CASE WHEN :status = 'PENDING' THEN :slot ELSE NULL END, :reason)
            ON CONFLICT (application_id, scheduled_slot) DO NOTHING
            """, new MapSqlParameterSource()
                .addValue("applicationId", applicationId)
                .addValue("slot", dbTimestamp(slot))
                .addValue("recipient", recipient)
                .addValue("status", status)
                .addValue("reason", reason));
    }

    void markSlotMaterialized(Instant slot, LocalDate assessmentDate, int academicYear) {
        jdbc.update("""
            INSERT INTO assessment_start_reminder_runs(scheduled_slot, assessment_date, academic_year)
            VALUES (:slot, :assessmentDate, :academicYear)
            ON CONFLICT (scheduled_slot) DO NOTHING
            """, Map.of("slot", dbTimestamp(slot), "assessmentDate", assessmentDate,
                "academicYear", academicYear));
    }

    Optional<Delivery> claimNext(Instant now, int maxAttempts, int leaseMinutes) {
        return transactions.execute(status -> jdbc.query("""
            WITH candidate AS (
                SELECT id
                  FROM assessment_start_reminder_deliveries
                 WHERE scheduled_slot <= :now
                   AND attempts < :maxAttempts
                   AND (
                       (status IN ('PENDING', 'FAILED') AND next_attempt_at <= :now)
                       OR (status = 'PROCESSING'
                           AND processing_started_at <= :now - (:leaseMinutes * INTERVAL '1 minute'))
                   )
                 ORDER BY scheduled_slot, next_attempt_at NULLS FIRST, id
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1
            )
            UPDATE assessment_start_reminder_deliveries delivery
               SET status = 'PROCESSING', attempts = attempts + 1,
                   processing_started_at = NOW(), updated_at = NOW(), last_error = NULL
              FROM candidate
             WHERE delivery.id = candidate.id
            RETURNING delivery.id, delivery.application_id, delivery.scheduled_slot, delivery.attempts
            """, Map.of("now", dbTimestamp(now), "maxAttempts", maxAttempts,
                "leaseMinutes", leaseMinutes),
            (rs, row) -> new Delivery(rs.getLong("id"), rs.getLong("application_id"),
                rs.getTimestamp("scheduled_slot").toInstant(), rs.getInt("attempts")))
            .stream().findFirst());
    }

    void markSent(long id, String recipient, String providerMessageId) {
        jdbc.update("""
            UPDATE assessment_start_reminder_deliveries
               SET status = 'SENT', recipient = :recipient, provider_message_id = :providerMessageId,
                   sent_at = NOW(), next_attempt_at = NULL, processing_started_at = NULL,
                   last_error = NULL, updated_at = NOW()
             WHERE id = :id AND status = 'PROCESSING'
            """, new MapSqlParameterSource().addValue("id", id)
                .addValue("recipient", recipient).addValue("providerMessageId", providerMessageId));
    }

    void markSkipped(long id, String recipient, String reason) {
        jdbc.update("""
            UPDATE assessment_start_reminder_deliveries
               SET status = 'SKIPPED', recipient = :recipient, last_error = :reason,
                   next_attempt_at = NULL, processing_started_at = NULL, updated_at = NOW()
             WHERE id = :id AND status = 'PROCESSING'
            """, Map.of("id", id, "recipient", recipient == null ? "" : recipient, "reason", reason));
    }

    void markFailed(long id, String recipient, String error, Instant nextAttemptAt) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", id);
        parameters.put("recipient", recipient == null ? "" : recipient);
        parameters.put("error", error);
        parameters.put("nextAttemptAt", nextAttemptAt == null ? null : dbTimestamp(nextAttemptAt));
        jdbc.update("""
            UPDATE assessment_start_reminder_deliveries
               SET status = 'FAILED', recipient = :recipient, last_error = :error,
                   next_attempt_at = :nextAttemptAt, processing_started_at = NULL, updated_at = NOW()
             WHERE id = :id AND status = 'PROCESSING'
            """, parameters);
    }

    private static OffsetDateTime dbTimestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    record Delivery(long id, long applicationId, Instant scheduledSlot, int attempts) {}
}
