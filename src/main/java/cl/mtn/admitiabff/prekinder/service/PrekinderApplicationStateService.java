package cl.mtn.admitiabff.prekinder.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Mantiene en un único lugar la regla de formalización de Prekínder. */
@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderApplicationStateService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PrekinderApplicationStateService(
        @Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    public FormalizationResult refresh(UUID applicationId, UUID actorId) {
        return transactions.execute(status -> refreshInTransaction(applicationId, actorId));
    }

    private FormalizationResult refreshInTransaction(UUID applicationId, UUID actorId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT application.status, application.version, application.eligibility_status,
                   application.payment_required, application.payment_status,
                   application.formal_submitted_at, process.academic_year,
                   EXISTS (
                       SELECT 1 FROM prekinder_complementary_forms form
                        JOIN applicants form_applicant ON form_applicant.family_id = form.family_id
                        WHERE form_applicant.applicant_id = application.applicant_id
                          AND form.process_id = application.process_id AND form.submitted
                   ) AS form_complete,
                   NOT EXISTS (
                       SELECT 1
                         FROM jsonb_array_elements_text(config.required_documents) required(category)
                        WHERE NOT EXISTS (
                            SELECT 1 FROM document_metadata document
                             WHERE document.application_id = application.application_id
                               AND upper(document.category) = upper(required.category)
                               AND document.review_status <> 'REJECTED'
                        )
                   ) AS documents_complete
                   , EXISTS (
                       SELECT 1 FROM application_correction_requests correction
                        WHERE correction.application_id = application.application_id
                          AND correction.status = 'OPEN'
                   ) AS correction_open
              FROM applications application
              JOIN admission_processes process ON process.process_id = application.process_id
              JOIN prekinder_process_configuration config ON config.process_id = application.process_id
             WHERE application.application_id = :id
             FOR UPDATE OF application
            """, Map.of("id", applicationId));
        if (rows.isEmpty()) throw new IllegalArgumentException("Postulación Prekínder no encontrada");
        Map<String, Object> application = rows.getFirst();
        String current = String.valueOf(application.get("status"));
        if (List.of("OFFERED", "WAITLISTED", "NOT_ADMITTED", "PENDING_ENROLLMENT_PAYMENT", "ENROLLED",
            "DECLINED", "EXPIRED", "WITHDRAWN", "CANCELLED", "INVALIDATED").contains(current)) {
            return result(applicationId);
        }

        boolean eligible = "VERIFIED".equals(application.get("eligibility_status"));
        boolean paymentComplete = !Boolean.TRUE.equals(application.get("payment_required"))
            || "PAID".equals(application.get("payment_status"));
        boolean formComplete = Boolean.TRUE.equals(application.get("form_complete"));
        boolean documentsComplete = Boolean.TRUE.equals(application.get("documents_complete"));
        boolean correctionOpen = Boolean.TRUE.equals(application.get("correction_open"));
        String next;
        if (!eligible) next = "PENDING_SEGMENT_VALIDATION";
        else if (!paymentComplete) next = "PENDING_PAYMENT";
        else if (correctionOpen) next = "REQUIRES_INFORMATION";
        else if (!formComplete || !documentsComplete) next = "FORM_PENDING";
        else next = "UNDER_ADMIN_REVIEW";

        if (next.equals(current) && (!"UNDER_ADMIN_REVIEW".equals(next)
            || application.get("formal_submitted_at") != null)) return result(applicationId);

        jdbc.update("""
            UPDATE applications
               SET status = :next,
                   formal_submitted_at = CASE WHEN :next = 'UNDER_ADMIN_REVIEW'
                                              THEN coalesce(formal_submitted_at, now()) ELSE formal_submitted_at END,
                   submitted_at = CASE WHEN :next = 'UNDER_ADMIN_REVIEW'
                                       THEN coalesce(submitted_at, now()) ELSE submitted_at END,
                   folio = CASE WHEN :next = 'UNDER_ADMIN_REVIEW' THEN coalesce(folio,
                       'PK-' || :academicYear || '-' || lpad(nextval('prekinder_folio_seq')::text, 6, '0'))
                       ELSE folio END,
                   version = version + 1,
                   updated_at = now()
             WHERE application_id = :id
            """, Map.of("id", applicationId, "next", next,
                "academicYear", application.get("academic_year")));
        long newVersion = ((Number) application.get("version")).longValue() + 1;
        jdbc.update("""
            INSERT INTO application_state_history(history_id, application_id, from_status, to_status,
                reason_code, actor_id, version)
            VALUES (:id, :applicationId, :fromStatus, :toStatus, 'PREREQUISITES_RECALCULATED',
                :actorId, :version)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", UUID.randomUUID()).addValue("applicationId", applicationId)
            .addValue("fromStatus", current).addValue("toStatus", next)
            .addValue("actorId", actorId).addValue("version", newVersion));
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result, metadata)
            VALUES (:id, :actorId, 'APPLICATION_PREREQUISITES_REFRESHED', 'APPLICATION', :applicationId,
                'SUCCESS', jsonb_build_object('from', :fromStatus, 'to', :toStatus))
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", UUID.randomUUID()).addValue("actorId", actorId)
            .addValue("applicationId", applicationId).addValue("fromStatus", current).addValue("toStatus", next));
        return result(applicationId);
    }

    private FormalizationResult result(UUID applicationId) {
        return jdbc.queryForObject("""
            SELECT application_id, status, formal_submitted_at, folio, version
              FROM applications WHERE application_id = :id
            """, Map.of("id", applicationId), (rs, row) -> new FormalizationResult(
            rs.getObject("application_id", UUID.class), rs.getString("status"),
            rs.getTimestamp("formal_submitted_at") == null ? null : rs.getTimestamp("formal_submitted_at").toInstant(),
            rs.getString("folio"), rs.getLong("version")));
    }

    public record FormalizationResult(UUID applicationId, String status,
        java.time.Instant formalSubmittedAt, String folio, long version) {}
}
