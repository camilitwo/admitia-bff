package cl.mtn.admitiabff.service.reminder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class ApplicationReminderRepository {
    private static final String STATE_QUERY = """
        WITH payment_facts AS (
            SELECT p.application_id,
                   BOOL_OR(p.status = 'PAID') AS has_paid,
                   BOOL_OR(UPPER(COALESCE(p.external_status, '')) = 'PAGADO') AS has_external_paid
              FROM payments p
             GROUP BY p.application_id
        ), latest_payment AS (
            SELECT DISTINCT ON (p.application_id) p.application_id, p.guardian_user_id
              FROM payments p
             ORDER BY p.application_id, p.created_at DESC, p.id DESC
        )
        SELECT app.id AS application_id,
               (app.deleted_at IS NULL AND app.is_archived = FALSE
                 AND app.academic_year = :academicYear
                 AND app.status IN ('PENDING', 'UNDER_REVIEW', 'DOCUMENTS_REQUESTED',
                    'PENDING_DOCUMENTS', 'INCOMPLETE', 'INTERVIEW_SCHEDULED', 'EXAM_SCHEDULED')) AS active,
               app.payment_required,
               app.payment_status = 'PAID' AS application_paid,
               COALESCE(pf.has_paid, FALSE) AS payment_paid,
               COALESCE(pf.has_external_paid, FALSE) AS external_paid,
               COALESCE(cf.is_submitted, FALSE) AS form_submitted,
               NULLIF(BTRIM(u.email), '') AS recipient,
               COALESCE(NULLIF(BTRIM(CONCAT_WS(' ', u.first_name, u.last_name)), ''), 'Familia') AS guardian_name,
               COALESCE(NULLIF(BTRIM(CONCAT_WS(' ', student.first_name,
                    student.paternal_last_name, student.maternal_last_name)), ''), 'Postulante') AS student_name,
               COALESCE(NULLIF(BTRIM(student.grade_applied), ''), 'curso informado') AS grade_applied,
               app.academic_year
          FROM applications app
          JOIN students student ON student.id = app.student_id
          LEFT JOIN guardians guardian ON guardian.id = app.guardian_id
          LEFT JOIN latest_payment lp ON lp.application_id = app.id
          LEFT JOIN users u ON u.id = COALESCE(lp.guardian_user_id, app.applicant_user_id, guardian.user_id)
          LEFT JOIN payment_facts pf ON pf.application_id = app.id
          LEFT JOIN complementary_forms cf ON cf.application_id = app.id
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    @Autowired
    ApplicationReminderRepository(@Qualifier("legacyDataSource") DataSource dataSource,
            @Qualifier("legacyTransactionManager") PlatformTransactionManager transactionManager) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
    }

    ApplicationReminderRepository(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    List<ApplicationState> findActiveStates(int academicYear) {
        return jdbc.query(STATE_QUERY + " WHERE app.deleted_at IS NULL AND app.is_archived = FALSE AND app.academic_year = :academicYear",
            Map.of("academicYear", academicYear), this::mapState);
    }

    Optional<ApplicationState> findState(long applicationId, int academicYear) {
        List<ApplicationState> rows = jdbc.query(STATE_QUERY + " WHERE app.id = :applicationId",
            Map.of("applicationId", applicationId, "academicYear", academicYear), this::mapState);
        return rows.stream().findFirst();
    }

    void insertDelivery(ApplicationState state, String reminderType, Instant slot, LocalDate slotDate,
            String status, String reason) {
        jdbc.update("""
            INSERT INTO application_reminder_deliveries(application_id, reminder_type, scheduled_slot,
                scheduled_date, recipient, status, next_attempt_at, last_error)
            VALUES (:applicationId, :reminderType, :slot, :slotDate, :recipient, :status,
                CASE WHEN :status = 'PENDING' THEN :slot ELSE NULL END, :reason)
            ON CONFLICT (application_id, reminder_type, scheduled_slot) DO NOTHING
            """, new MapSqlParameterSource()
                .addValue("applicationId", state.applicationId())
                .addValue("reminderType", reminderType)
                .addValue("slot", dbTimestamp(slot))
                .addValue("slotDate", slotDate)
                .addValue("recipient", state.recipient())
                .addValue("status", status)
                .addValue("reason", reason));
    }

    boolean isSlotMaterialized(Instant slot) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM application_reminder_runs WHERE scheduled_slot = :slot
            """, Map.of("slot", dbTimestamp(slot)), Integer.class);
        return count != null && count > 0;
    }

    void markSlotMaterialized(Instant slot, LocalDate slotDate, int academicYear) {
        jdbc.update("""
            INSERT INTO application_reminder_runs(scheduled_slot, scheduled_date, academic_year)
            VALUES (:slot, :slotDate, :academicYear)
            ON CONFLICT (scheduled_slot) DO NOTHING
            """, Map.of("slot", dbTimestamp(slot), "slotDate", slotDate, "academicYear", academicYear));
    }

    Optional<Delivery> claimNext(LocalDate today, int maxAttempts, int leaseMinutes) {
        return transactions.execute(status -> jdbc.query("""
            WITH candidate AS (
                SELECT id
                  FROM application_reminder_deliveries
                 WHERE scheduled_date = :today
                   AND attempts < :maxAttempts
                   AND (
                       (status IN ('PENDING', 'FAILED') AND next_attempt_at <= NOW())
                       OR (status = 'PROCESSING'
                           AND processing_started_at <= NOW() - (:leaseMinutes * INTERVAL '1 minute'))
                   )
                 ORDER BY next_attempt_at NULLS FIRST, id
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1
            )
            UPDATE application_reminder_deliveries delivery
               SET status = 'PROCESSING', attempts = attempts + 1,
                   processing_started_at = NOW(), updated_at = NOW(), last_error = NULL
              FROM candidate
             WHERE delivery.id = candidate.id
            RETURNING delivery.id, delivery.application_id, delivery.reminder_type,
                      delivery.scheduled_slot, delivery.scheduled_date, delivery.attempts
            """, Map.of("today", today, "maxAttempts", maxAttempts, "leaseMinutes", leaseMinutes),
            (rs, row) -> new Delivery(rs.getLong("id"), rs.getLong("application_id"),
                rs.getString("reminder_type"), rs.getTimestamp("scheduled_slot").toInstant(),
                rs.getObject("scheduled_date", LocalDate.class), rs.getInt("attempts")))
            .stream().findFirst());
    }

    void markSent(long id, String recipient, String providerMessageId) {
        jdbc.update("""
            UPDATE application_reminder_deliveries
               SET status = 'SENT', recipient = :recipient, provider_message_id = :providerMessageId,
                   sent_at = NOW(), next_attempt_at = NULL, processing_started_at = NULL,
                   last_error = NULL, updated_at = NOW()
             WHERE id = :id AND status = 'PROCESSING'
            """, new MapSqlParameterSource()
                .addValue("id", id).addValue("recipient", recipient)
                .addValue("providerMessageId", providerMessageId));
    }

    void markSkipped(long id, String recipient, String reason) {
        jdbc.update("""
            UPDATE application_reminder_deliveries
               SET status = 'SKIPPED', recipient = :recipient, last_error = :reason,
                   next_attempt_at = NULL, processing_started_at = NULL, updated_at = NOW()
             WHERE id = :id AND status = 'PROCESSING'
            """, Map.of("id", id, "recipient", recipient == null ? "" : recipient, "reason", reason));
    }

    void markFailed(long id, String recipient, String error, Instant nextAttemptAt) {
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("id", id);
        parameters.put("recipient", recipient == null ? "" : recipient);
        parameters.put("error", error);
        parameters.put("nextAttemptAt", nextAttemptAt == null ? null : dbTimestamp(nextAttemptAt));
        jdbc.update("""
            UPDATE application_reminder_deliveries
               SET status = 'FAILED', recipient = :recipient, last_error = :error,
                   next_attempt_at = :nextAttemptAt, processing_started_at = NULL, updated_at = NOW()
             WHERE id = :id AND status = 'PROCESSING'
            """, parameters);
    }

    private ApplicationState mapState(ResultSet rs, int row) throws SQLException {
        return new ApplicationState(rs.getLong("application_id"), rs.getBoolean("active"),
            rs.getBoolean("payment_required"), rs.getBoolean("application_paid"),
            rs.getBoolean("payment_paid"), rs.getBoolean("external_paid"),
            rs.getBoolean("form_submitted"), rs.getString("recipient"),
            rs.getString("guardian_name"), rs.getString("student_name"),
            rs.getString("grade_applied"), (Integer) rs.getObject("academic_year"));
    }

    private static OffsetDateTime dbTimestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    record ApplicationState(long applicationId, boolean active, boolean paymentRequired,
        boolean applicationPaid, boolean paymentPaid, boolean externalPaid, boolean formSubmitted,
        String recipient, String guardianName, String studentName, String gradeApplied, Integer academicYear) {
        boolean paymentConfirmed() { return applicationPaid || paymentPaid; }
    }

    record Delivery(long id, long applicationId, String reminderType, Instant scheduledSlot,
        LocalDate scheduledDate, int attempts) {}
}
