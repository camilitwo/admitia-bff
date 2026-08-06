package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderReportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final EnvelopeEncryptionService encryption;

    public PrekinderReportService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
                                  @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
                                  PrekinderAccessService access, EnvelopeEncryptionService encryption) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.encryption = encryption;
    }

    public ReportView report(UUID reportId) {
        PrekinderActor actor = access.requireEvaluator();
        assertReportAccess(reportId, actor);
        ReportHeader header = header(reportId);
        List<CriterionView> criteria = jdbc.query("""
            SELECT c.criterion_id, c.code, c.name, c.descriptor, c.position,
                   r.response_id, r.selected_option_id, r.not_observed, r.observed_value, coalesce(r.version, 0) AS response_version
              FROM evaluation_criteria c
              LEFT JOIN evaluator_report_responses r ON r.criterion_id = c.criterion_id AND r.report_id = :reportId
             WHERE c.evaluation_template_version_id = :templateVersionId
             ORDER BY c.position
            """, Map.of("reportId", reportId, "templateVersionId", header.templateVersionId()), (rs, row) -> {
                UUID criterionId = rs.getObject("criterion_id", UUID.class);
                List<OptionView> options = jdbc.query("""
                    SELECT option_id, value, label, descriptor, position FROM evaluation_options
                     WHERE criterion_id = :criterionId ORDER BY position
                    """, Map.of("criterionId", criterionId), (optionRs, optionRow) -> new OptionView(
                        optionRs.getObject("option_id", UUID.class), optionRs.getBigDecimal("value"),
                        optionRs.getString("label"), optionRs.getString("descriptor"), optionRs.getInt("position")));
                return new CriterionView(criterionId, rs.getString("code"), rs.getString("name"),
                    rs.getString("descriptor"), rs.getInt("position"), options,
                    rs.getObject("response_id", UUID.class), rs.getObject("selected_option_id", UUID.class),
                    rs.getBoolean("not_observed"), rs.getBigDecimal("observed_value"), rs.getLong("response_version"));
            });
        NoteView note = note(reportId);
        return new ReportView(header, editable(header, Instant.now()), criteria, note);
    }

    public ReportView saveResponse(UUID reportId, UUID criterionId, UUID optionId, boolean notObserved,
                                   long expectedVersion, UUID operationId) {
        PrekinderActor actor = access.requireEvaluator();
        assertReportAccess(reportId, actor);
        return transactions.execute(status -> {
            ReportHeader header = header(reportId);
            assertEditable(header);
            Long duplicate = jdbc.queryForObject("SELECT count(*) FROM evaluator_report_responses WHERE operation_id = :id",
                Map.of("id", operationId), Long.class);
            if (duplicate != null && duplicate > 0) return report(reportId);
            BigDecimal value = null;
            if (!notObserved) {
                if (optionId == null) throw new IllegalArgumentException("Selecciona una alternativa");
                List<BigDecimal> values = jdbc.queryForList("""
                    SELECT o.value FROM evaluation_options o JOIN evaluation_criteria c ON c.criterion_id = o.criterion_id
                     WHERE o.option_id = :optionId AND c.criterion_id = :criterionId
                       AND c.evaluation_template_version_id = :templateVersionId
                    """, Map.of("optionId", optionId, "criterionId", criterionId,
                    "templateVersionId", header.templateVersionId()), BigDecimal.class);
                if (values.isEmpty()) throw new IllegalArgumentException("Alternativa inválida");
                value = values.getFirst();
            }
            if (expectedVersion == 0) {
                try {
                    jdbc.update("""
                        INSERT INTO evaluator_report_responses(response_id, report_id, criterion_id, selected_option_id,
                            not_observed, observed_value, operation_id)
                        VALUES (:id, :reportId, :criterionId, :optionId, :notObserved, :value, :operationId)
                        """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("reportId", reportId)
                        .addValue("criterionId", criterionId).addValue("optionId", optionId)
                        .addValue("notObserved", notObserved).addValue("value", value).addValue("operationId", operationId));
                } catch (DataIntegrityViolationException exception) {
                    throw new VersionConflictException("La respuesta cambió");
                }
            } else {
                int updated = jdbc.update("""
                    UPDATE evaluator_report_responses SET selected_option_id = :optionId,
                        not_observed = :notObserved, observed_value = :value, operation_id = :operationId,
                        version = version + 1, updated_at = now()
                     WHERE report_id = :reportId AND criterion_id = :criterionId AND version = :expectedVersion
                    """, new MapSqlParameterSource().addValue("reportId", reportId).addValue("criterionId", criterionId)
                    .addValue("optionId", optionId).addValue("notObserved", notObserved).addValue("value", value)
                    .addValue("operationId", operationId).addValue("expectedVersion", expectedVersion));
                if (updated != 1) throw new VersionConflictException("La respuesta cambió");
            }
            jdbc.update("""
                UPDATE evaluator_reports SET status = CASE WHEN status = 'PENDING' THEN 'IN_PROGRESS' ELSE status END,
                    version = version + 1, updated_at = now() WHERE report_id = :id
                """, Map.of("id", reportId));
            audit(actor.id(), "REPORT_RESPONSE_SAVED", reportId, Map.of("criterionId", criterionId));
            return report(reportId);
        });
    }

    public ReportView saveNote(UUID reportId, String content, long expectedVersion, UUID operationId) {
        PrekinderActor actor = access.requireEvaluator();
        assertReportAccess(reportId, actor);
        return transactions.execute(status -> {
            ReportHeader header = header(reportId);
            assertEditable(header);
            Long duplicate = jdbc.queryForObject("SELECT count(*) FROM evaluator_report_notes WHERE operation_id = :id",
                Map.of("id", operationId), Long.class);
            if (duplicate != null && duplicate > 0) return report(reportId);
            EncryptedPayload payload = encryption.encrypt(content == null ? "" : content.trim(),
                "prekinder|report-note|" + reportId);
            MapSqlParameterSource values = encryptedValues(payload).addValue("reportId", reportId)
                .addValue("operationId", operationId).addValue("expectedVersion", expectedVersion);
            if (expectedVersion == 0) {
                try {
                    jdbc.update("""
                        INSERT INTO evaluator_report_notes(note_id, report_id, ciphertext, iv, wrapped_dek,
                            wrapped_dek_iv, key_version, operation_id)
                        VALUES (:id, :reportId, :ciphertext, :iv, :wrappedDek, :wrappedDekIv, :keyVersion, :operationId)
                        """, values.addValue("id", UUID.randomUUID()));
                } catch (DataIntegrityViolationException exception) {
                    throw new VersionConflictException("La observación cambió");
                }
            } else {
                int updated = jdbc.update("""
                    UPDATE evaluator_report_notes SET ciphertext = :ciphertext, iv = :iv, wrapped_dek = :wrappedDek,
                        wrapped_dek_iv = :wrappedDekIv, key_version = :keyVersion, operation_id = :operationId,
                        version = version + 1, updated_at = now()
                     WHERE report_id = :reportId AND version = :expectedVersion
                    """, values);
                if (updated != 1) throw new VersionConflictException("La observación cambió");
            }
            jdbc.update("UPDATE evaluator_reports SET version = version + 1, updated_at = now() WHERE report_id = :id",
                Map.of("id", reportId));
            audit(actor.id(), "REPORT_NOTE_SAVED", reportId, Map.of());
            return report(reportId);
        });
    }

    public ReportView complete(UUID reportId, long expectedVersion) {
        PrekinderActor actor = access.requireEvaluator();
        assertReportAccess(reportId, actor);
        return transactions.execute(status -> {
            ReportHeader header = header(reportId);
            assertEditable(header);
            Long missing = jdbc.queryForObject("""
                SELECT count(*) FROM evaluation_criteria c
                 WHERE c.evaluation_template_version_id = :templateVersionId AND c.required = true
                   AND NOT EXISTS (SELECT 1 FROM evaluator_report_responses r
                                    WHERE r.report_id = :reportId AND r.criterion_id = c.criterion_id)
                """, Map.of("templateVersionId", header.templateVersionId(), "reportId", reportId), Long.class);
            if (missing != null && missing > 0) {
                throw PrekinderDomainException.conflict("REPORT_INCOMPLETE", "Completa todos los criterios antes de finalizar");
            }
            BigDecimal score = jdbc.queryForObject("""
                SELECT coalesce(sum(observed_value), 0) FROM evaluator_report_responses WHERE report_id = :reportId
                """, Map.of("reportId", reportId), BigDecimal.class);
            BigDecimal maximum = jdbc.queryForObject("""
                SELECT maximum_score FROM evaluation_template_versions WHERE evaluation_template_version_id = :id
                """, Map.of("id", header.templateVersionId()), BigDecimal.class);
            int updated = jdbc.update("""
                UPDATE evaluator_reports SET status = 'COMPLETED', raw_score = :score, maximum_score = :maximum,
                    completed_at = now(), version = version + 1, updated_at = now()
                 WHERE report_id = :reportId AND version = :version AND status IN ('PENDING','IN_PROGRESS','REOPENED')
                """, new MapSqlParameterSource().addValue("reportId", reportId).addValue("version", expectedVersion)
                .addValue("score", score).addValue("maximum", maximum));
            if (updated != 1) throw new VersionConflictException("El informe cambió");
            audit(actor.id(), "REPORT_COMPLETED", reportId, Map.of());
            return report(reportId);
        });
    }

    public ReportView extend(UUID reportId, Instant validUntil, String reason) {
        PrekinderActor actor = access.requireAdmin();
        if (validUntil == null || !validUntil.isAfter(Instant.now()) || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Indica una extensión futura y su motivo");
        }
        EncryptedPayload payload = encryption.encrypt(reason.trim(), "prekinder|report-extension|" + reportId + "|" + validUntil);
        jdbc.update("""
            INSERT INTO report_edit_extensions(extension_id, report_id, valid_until, reason_ciphertext, reason_iv,
                reason_wrapped_dek, reason_wrapped_dek_iv, reason_key_version, granted_by)
            VALUES (:id, :reportId, :validUntil, :ciphertext, :iv, :wrappedDek, :wrappedDekIv, :keyVersion, :actorId)
            """, encryptedValues(payload).addValue("id", UUID.randomUUID()).addValue("reportId", reportId)
            .addValue("validUntil", Timestamp.from(validUntil)).addValue("actorId", actor.id()));
        jdbc.update("""
            UPDATE evaluator_reports SET status = CASE WHEN status IN ('COMPLETED','LOCKED') THEN 'REOPENED' ELSE status END,
                version = version + 1, updated_at = now() WHERE report_id = :id
            """, Map.of("id", reportId));
        audit(actor.id(), "REPORT_EXTENDED", reportId, Map.of("validUntil", validUntil.toString()));
        return reportForAdmin(reportId);
    }

    public ReportView reportForAdmin(UUID reportId) {
        access.requireAdmin();
        ReportHeader header = header(reportId);
        List<CriterionView> criteria = jdbc.query("""
            SELECT c.criterion_id, c.code, c.name, c.descriptor, c.position,
                   r.response_id, r.selected_option_id, r.not_observed, r.observed_value, coalesce(r.version, 0) AS response_version
              FROM evaluation_criteria c LEFT JOIN evaluator_report_responses r
                ON r.criterion_id = c.criterion_id AND r.report_id = :reportId
             WHERE c.evaluation_template_version_id = :templateVersionId ORDER BY c.position
            """, Map.of("reportId", reportId, "templateVersionId", header.templateVersionId()), (rs, row) ->
                new CriterionView(rs.getObject("criterion_id", UUID.class), rs.getString("code"), rs.getString("name"),
                    rs.getString("descriptor"), rs.getInt("position"), List.of(), rs.getObject("response_id", UUID.class),
                    rs.getObject("selected_option_id", UUID.class), rs.getBoolean("not_observed"),
                    rs.getBigDecimal("observed_value"), rs.getLong("response_version")));
        return new ReportView(header, editable(header, Instant.now()), criteria, note(reportId));
    }

    private ReportHeader header(UUID reportId) {
        return jdbc.queryForObject("""
            SELECT r.report_id, r.group_id, r.application_id, r.evaluator_id, r.evaluation_template_version_id,
                   r.status, r.raw_score, r.maximum_score, r.version, g.stage, g.code AS group_code,
                   g.starts_at, g.ends_at, room.name AS room_name
              FROM evaluator_reports r JOIN evaluation_groups g ON g.group_id = r.group_id
              JOIN prekinder_rooms room ON room.room_id = g.room_id
             WHERE r.report_id = :id
            """, Map.of("id", reportId), (rs, row) -> {
                UUID applicationId = rs.getObject("application_id", UUID.class);
                return new ReportHeader(reportId,
                rs.getObject("group_id", UUID.class), applicationId, applicantName(applicationId),
                rs.getObject("evaluator_id", UUID.class), rs.getObject("evaluation_template_version_id", UUID.class),
                rs.getString("status"), rs.getBigDecimal("raw_score"), rs.getBigDecimal("maximum_score"),
                rs.getLong("version"), rs.getString("stage"), rs.getString("group_code"),
                instant(rs.getTimestamp("starts_at")), instant(rs.getTimestamp("ends_at")), rs.getString("room_name"));
            });
    }

    private String applicantName(UUID applicationId) {
        return jdbc.queryForObject("""
            SELECT a.applicant_id, ap.identity_ciphertext, ap.identity_iv, ap.identity_wrapped_dek,
                   ap.identity_wrapped_dek_iv, ap.identity_key_version
              FROM applications a JOIN applicants ap ON ap.applicant_id = a.applicant_id
             WHERE a.application_id = :id
            """, Map.of("id", applicationId), (rs, row) -> {
                UUID applicantId = rs.getObject("applicant_id", UUID.class);
                String json = encryption.decrypt(new EncryptedPayload(rs.getString("identity_ciphertext"),
                    rs.getString("identity_iv"), rs.getString("identity_wrapped_dek"),
                    rs.getString("identity_wrapped_dek_iv"), rs.getString("identity_key_version")),
                    "prekinder|applicants|" + applicantId + "|application:" + applicationId + "|identity");
                try {
                    var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                    return (node.path("firstName").asText() + " " + node.path("paternalLastName").asText()).trim();
                } catch (Exception exception) {
                    return "Postulante";
                }
            });
    }

    private NoteView note(UUID reportId) {
        List<NoteView> notes = jdbc.query("""
            SELECT note_id, ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version, version
              FROM evaluator_report_notes WHERE report_id = :id
            """, Map.of("id", reportId), (rs, row) -> new NoteView(rs.getObject("note_id", UUID.class),
                encryption.decrypt(new EncryptedPayload(rs.getString("ciphertext"), rs.getString("iv"),
                    rs.getString("wrapped_dek"), rs.getString("wrapped_dek_iv"), rs.getString("key_version")),
                    "prekinder|report-note|" + reportId), rs.getLong("version")));
        return notes.isEmpty() ? new NoteView(null, "", 0) : notes.getFirst();
    }

    private void assertReportAccess(UUID reportId, PrekinderActor actor) {
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM evaluator_reports r
             WHERE r.report_id = :reportId AND (r.evaluator_id = :actorId OR :admin = true)
            """, Map.of("reportId", reportId, "actorId", actor.id(), "admin",
                List.of("ADMIN", "COORDINATOR", "CYCLE_DIRECTOR").contains(actor.role())), Long.class);
        if (count == null || count == 0) throw PrekinderDomainException.forbidden("NOT_ASSIGNED", "El informe no está asignado a este profesional");
    }

    private void assertEditable(ReportHeader header) {
        if (header.status().equals("COMPLETED")) throw PrekinderDomainException.conflict("REPORT_COMPLETED", "El informe ya fue finalizado");
        if (!editable(header, Instant.now())) {
            throw PrekinderDomainException.forbidden("EDIT_WINDOW_CLOSED", "La ventana de edición está cerrada");
        }
    }

    private boolean editable(ReportHeader header, Instant now) {
        if (!now.isBefore(header.startsAt().minus(Duration.ofMinutes(3)))
                && !now.isAfter(header.endsAt().plus(Duration.ofMinutes(10)))) return true;
        Long extensions = jdbc.queryForObject("""
            SELECT count(*) FROM report_edit_extensions WHERE report_id = :id AND valid_until >= now()
            """, Map.of("id", header.reportId()), Long.class);
        return extensions != null && extensions > 0;
    }

    private MapSqlParameterSource encryptedValues(EncryptedPayload payload) {
        return new MapSqlParameterSource().addValue("ciphertext", payload.ciphertext()).addValue("iv", payload.iv())
            .addValue("wrappedDek", payload.wrappedDek()).addValue("wrappedDekIv", payload.wrappedDekIv())
            .addValue("keyVersion", payload.keyVersion());
    }

    private void audit(UUID actorId, String action, UUID reportId, Map<String, ?> metadata) {
        String json;
        try { json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata); }
        catch (Exception exception) { json = "{}"; }
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result, metadata)
            VALUES (:id, :actorId, :action, 'EVALUATOR_REPORT', :reportId, 'SUCCESS', CAST(:metadata AS jsonb))
            """, Map.of("id", UUID.randomUUID(), "actorId", actorId, "action", action,
                "reportId", reportId, "metadata", json));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record ReportHeader(UUID reportId, UUID groupId, UUID applicationId, String applicantName, UUID evaluatorId,
                               UUID templateVersionId, String status, BigDecimal rawScore, BigDecimal maximumScore,
                               long version, String stage, String groupCode, Instant startsAt, Instant endsAt,
                               String roomName) {}
    public record OptionView(UUID optionId, BigDecimal value, String label, String descriptor, int position) {}
    public record CriterionView(UUID criterionId, String code, String name, String descriptor, int position,
                                List<OptionView> options, UUID responseId, UUID selectedOptionId,
                                boolean notObserved, BigDecimal observedValue, long responseVersion) {}
    public record NoteView(UUID noteId, String content, long version) {}
    public record ReportView(ReportHeader header, boolean editableNow, List<CriterionView> criteria, NoteView note) {}
}
