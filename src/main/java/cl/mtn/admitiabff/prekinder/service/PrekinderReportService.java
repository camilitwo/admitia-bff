package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.realtime.PrekinderRealtimeNotifier;
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
    private final PrekinderRealtimeNotifier realtime;

    public PrekinderReportService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
                                  @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
                                  PrekinderAccessService access, EnvelopeEncryptionService encryption,
                                  PrekinderRealtimeNotifier realtime) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.encryption = encryption;
        this.realtime = realtime;
    }

    public ReportView report(UUID reportId) {
        PrekinderActor actor = access.requireEvaluator();
        assertReportAccess(reportId, actor);
        ReportHeader header = header(reportId);
        List<CriterionView> criteria = jdbc.query("""
            SELECT c.criterion_id, c.code, c.name, c.descriptor, c.position,
                   r.response_id, r.selected_option_id, r.not_observed, r.observation_state,
                   r.observed_value, coalesce(r.version, 0) AS response_version
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
                    rs.getBoolean("not_observed"), rs.getString("observation_state"),
                    rs.getBigDecimal("observed_value"), rs.getLong("response_version"));
            });
        NoteView note = note(reportId);
        return new ReportView(header, editable(header, Instant.now()), criteria, note);
    }

    public ReportView saveResponse(UUID reportId, UUID criterionId, UUID optionId, boolean notObserved,
                                   long expectedVersion, UUID operationId) {
        return saveResponse(reportId, criterionId, optionId,
            notObserved ? "NOT_OBSERVED" : "OBSERVED", expectedVersion, operationId);
    }

    public ReportView saveResponse(UUID reportId, UUID criterionId, UUID optionId, String observationState,
                                   long expectedVersion, UUID operationId) {
        PrekinderActor actor = access.requireEvaluator();
        assertReportAccess(reportId, actor);
        return transactions.execute(status -> {
            ReportHeader header = header(reportId);
            assertEditable(header);
            Long duplicate = jdbc.queryForObject("SELECT count(*) FROM evaluator_report_responses WHERE operation_id = :id",
                Map.of("id", operationId), Long.class);
            if (duplicate != null && duplicate > 0) return report(reportId);
            String normalizedState = observationState == null ? "OBSERVED" : observationState.trim().toUpperCase();
            if (!List.of("OBSERVED", "NOT_OBSERVED", "NOT_APPLICABLE", "PENDING").contains(normalizedState))
                throw new IllegalArgumentException("Estado de observación inválido");
            boolean notObserved = !"OBSERVED".equals(normalizedState);
            UUID effectiveOptionId = "OBSERVED".equals(normalizedState) ? optionId : null;
            BigDecimal value = null;
            if ("OBSERVED".equals(normalizedState)) {
                if (effectiveOptionId == null) throw new IllegalArgumentException("Selecciona una alternativa");
                List<BigDecimal> values = jdbc.queryForList("""
                    SELECT o.value FROM evaluation_options o JOIN evaluation_criteria c ON c.criterion_id = o.criterion_id
                     WHERE o.option_id = :optionId AND c.criterion_id = :criterionId
                       AND c.evaluation_template_version_id = :templateVersionId
                    """, Map.of("optionId", effectiveOptionId, "criterionId", criterionId,
                    "templateVersionId", header.templateVersionId()), BigDecimal.class);
                if (values.isEmpty()) throw new IllegalArgumentException("Alternativa inválida");
                value = values.getFirst();
            }
            BigDecimal observedValue = value;
            if (expectedVersion == 0) {
                try {
                    jdbc.update("""
                        INSERT INTO evaluator_report_responses(response_id, report_id, criterion_id, selected_option_id,
                            not_observed, observation_state, observed_value, operation_id)
                        VALUES (:id, :reportId, :criterionId, :optionId, :notObserved, :observationState, :value, :operationId)
                        """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("reportId", reportId)
                        .addValue("criterionId", criterionId).addValue("optionId", effectiveOptionId)
                        .addValue("notObserved", notObserved).addValue("observationState", normalizedState)
                        .addValue("value", observedValue).addValue("operationId", operationId));
                } catch (DataIntegrityViolationException exception) {
                    throw new VersionConflictException("La respuesta cambió");
                }
            } else {
                int updated = jdbc.update("""
                    UPDATE evaluator_report_responses SET selected_option_id = :optionId,
                        not_observed = :notObserved, observed_value = :value, operation_id = :operationId,
                        observation_state = :observationState,
                        version = version + 1, updated_at = now()
                     WHERE report_id = :reportId AND criterion_id = :criterionId AND version = :expectedVersion
                    """, new MapSqlParameterSource().addValue("reportId", reportId).addValue("criterionId", criterionId)
                    .addValue("optionId", effectiveOptionId).addValue("notObserved", notObserved).addValue("value", observedValue)
                    .addValue("observationState", normalizedState).addValue("operationId", operationId)
                    .addValue("expectedVersion", expectedVersion));
                if (updated != 1) throw new VersionConflictException("La respuesta cambió");
            }
            jdbc.update("""
                UPDATE evaluator_reports SET status = CASE WHEN status = 'PENDING' THEN 'IN_PROGRESS' ELSE status END,
                    version = version + 1, updated_at = now() WHERE report_id = :id
                """, Map.of("id", reportId));
            audit(actor.id(), "REPORT_RESPONSE_SAVED", reportId, Map.of("criterionId", criterionId));
            realtime.notifyAfterCommit(actor.id(), processId(header.groupId()), "EVALUATOR_REPORT_UPDATED");
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
            realtime.notifyAfterCommit(actor.id(), processId(header.groupId()), "EVALUATOR_REPORT_UPDATED");
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
                                    WHERE r.report_id = :reportId AND r.criterion_id = c.criterion_id
                                      AND r.observation_state <> 'PENDING')
                """, Map.of("templateVersionId", header.templateVersionId(), "reportId", reportId), Long.class);
            if (missing != null && missing > 0) {
                throw PrekinderDomainException.conflict("REPORT_INCOMPLETE", "Completa todos los criterios antes de finalizar");
            }
            Boolean scoring = jdbc.queryForObject("""
                SELECT coalesce(policy.scoring, false)
                  FROM evaluator_reports report
                  JOIN evaluation_groups group_data ON group_data.group_id = report.group_id
                  LEFT JOIN group_instrument_assignments assignment
                    ON assignment.assignment_id = report.instrument_assignment_id
                  LEFT JOIN process_instrument_policies policy
                    ON policy.process_id = group_data.process_id
                   AND policy.instrument_code = assignment.instrument_code
                 WHERE report.report_id = :reportId
                """, Map.of("reportId", reportId), Boolean.class);
            BigDecimal calculatedScore = jdbc.queryForObject("""
                SELECT coalesce(sum(observed_value), 0) FROM evaluator_report_responses WHERE report_id = :reportId
                """, Map.of("reportId", reportId), BigDecimal.class);
            BigDecimal calculatedMaximum = jdbc.queryForObject("""
                SELECT maximum_score FROM evaluation_template_versions WHERE evaluation_template_version_id = :id
                """, Map.of("id", header.templateVersionId()), BigDecimal.class);
            BigDecimal score = Boolean.TRUE.equals(scoring) ? calculatedScore : null;
            BigDecimal maximum = Boolean.TRUE.equals(scoring) ? calculatedMaximum : null;
            int updated = jdbc.update("""
                UPDATE evaluator_reports SET status = 'SUBMITTED', raw_score = :score, maximum_score = :maximum,
                    submitted_at = now(), completed_at = now(), version = version + 1, updated_at = now()
                 WHERE report_id = :reportId AND version = :version
                   AND status IN ('PENDING','IN_PROGRESS','REOPENED','RETURNED')
                """, new MapSqlParameterSource().addValue("reportId", reportId).addValue("version", expectedVersion)
                .addValue("score", score).addValue("maximum", maximum));
            if (updated != 1) throw new VersionConflictException("El informe cambió");
            audit(actor.id(), "REPORT_SUBMITTED", reportId, Map.of());
            realtime.notifyAfterCommit(actor.id(), processId(header.groupId()), "EVALUATOR_REPORT_COMPLETED");
            return report(reportId);
        });
    }

    public ReportView markPaperCapture(UUID reportId, Instant observedAt, long expectedVersion) {
        PrekinderActor actor = access.requireOperations();
        if (observedAt == null || observedAt.isAfter(Instant.now()))
            throw new IllegalArgumentException("La fecha real de aplicación es obligatoria y no puede ser futura");
        int updated = jdbc.update("""
            UPDATE evaluator_reports SET capture_origin = 'PAPER', observed_at = :observedAt,
                entered_by = :actorId, second_validated_by = NULL, second_validated_at = NULL,
                version = version + 1, updated_at = now()
             WHERE report_id = :reportId AND version = :version
               AND status IN ('PENDING','IN_PROGRESS','RETURNED','REOPENED')
            """, new MapSqlParameterSource().addValue("reportId", reportId)
            .addValue("version", expectedVersion).addValue("observedAt", Timestamp.from(observedAt))
            .addValue("actorId", actor.id()));
        if (updated != 1) throw new VersionConflictException("El informe cambió o ya no admite digitación manual");
        audit(actor.id(), "REPORT_PAPER_CAPTURE_DECLARED", reportId, Map.of("observedAt", observedAt.toString()));
        return reportForReview(reportId);
    }

    public ReportView review(UUID reportId, String decision, String reason, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        if (!List.of("VALIDATED", "RETURNED").contains(normalized))
            throw new IllegalArgumentException("La revisión debe ser VALIDATED o RETURNED");
        if ("RETURNED".equals(normalized) && (reason == null || reason.isBlank()))
            throw new IllegalArgumentException("La devolución requiere un motivo");
        return transactions.execute(status -> {
            ReportHeader header = header(reportId);
            Map<String, Object> capture = jdbc.queryForMap("""
                SELECT capture_origin, entered_by FROM evaluator_reports WHERE report_id = :reportId
                """, Map.of("reportId", reportId));
            if ("VALIDATED".equals(normalized) && "PAPER".equals(capture.get("capture_origin"))
                && actor.id().equals(capture.get("entered_by")))
                throw PrekinderDomainException.conflict("SECOND_VALIDATION_REQUIRED",
                    "La digitación desde papel debe ser validada por una persona distinta");
            EncryptedPayload encrypted = reason == null || reason.isBlank() ? null
                : encryption.encrypt(reason.trim(), "prekinder|report-review|" + reportId + "|" + expectedVersion);
            MapSqlParameterSource review = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("reportId", reportId)
                .addValue("decision", normalized).addValue("actorId", actor.id())
                .addValue("ciphertext", encrypted == null ? null : encrypted.ciphertext())
                .addValue("iv", encrypted == null ? null : encrypted.iv())
                .addValue("wrappedDek", encrypted == null ? null : encrypted.wrappedDek())
                .addValue("wrappedDekIv", encrypted == null ? null : encrypted.wrappedDekIv())
                .addValue("keyVersion", encrypted == null ? null : encrypted.keyVersion());
            int updated = jdbc.update("""
                UPDATE evaluator_reports SET status = :decision,
                    validated_at = CASE WHEN :decision = 'VALIDATED' THEN now() ELSE NULL END,
                    validated_by = CASE WHEN :decision = 'VALIDATED' THEN :actorId ELSE NULL END,
                    returned_at = CASE WHEN :decision = 'RETURNED' THEN now() ELSE NULL END,
                    second_validated_by = CASE WHEN :decision = 'VALIDATED' AND capture_origin = 'PAPER'
                        THEN :actorId ELSE second_validated_by END,
                    second_validated_at = CASE WHEN :decision = 'VALIDATED' AND capture_origin = 'PAPER'
                        THEN now() ELSE second_validated_at END,
                    version = version + 1, updated_at = now()
                 WHERE report_id = :reportId AND version = :version AND status = 'SUBMITTED'
                """, new MapSqlParameterSource().addValue("decision", normalized).addValue("actorId", actor.id())
                .addValue("reportId", reportId).addValue("version", expectedVersion));
            if (updated != 1) throw new VersionConflictException("El informe cambió o no está enviado");
            jdbc.update("""
                INSERT INTO evaluator_report_reviews(review_id, report_id, decision, reason_ciphertext,
                    reason_iv, reason_wrapped_dek, reason_wrapped_dek_iv, reason_key_version, reviewed_by)
                VALUES (:id, :reportId, :decision, :ciphertext, :iv, :wrappedDek, :wrappedDekIv,
                    :keyVersion, :actorId)
                """, review);
            audit(actor.id(), "REPORT_" + normalized, reportId, Map.of());
            realtime.notifyAfterCommit(header.evaluatorId(), processId(header.groupId()), "EVALUATOR_REPORT_" + normalized);
            return reportForAdmin(reportId);
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
            UPDATE evaluator_reports SET status = CASE WHEN status IN ('VALIDATED','LOCKED') THEN 'REOPENED' ELSE status END,
                version = version + 1, updated_at = now() WHERE report_id = :id
            """, Map.of("id", reportId));
        audit(actor.id(), "REPORT_EXTENDED", reportId, Map.of("validUntil", validUntil.toString()));
        UUID evaluatorId = header(reportId).evaluatorId();
        ReportHeader reopened = header(reportId);
        realtime.notifyAfterCommit(evaluatorId, processId(reopened.groupId()), "EVALUATOR_REPORT_REOPENED");
        return reportForAdmin(reportId);
    }

    public ReportView reportForAdmin(UUID reportId) {
        access.requireAdmin();
        return reportForReview(reportId);
    }

    private ReportView reportForReview(UUID reportId) {
        ReportHeader header = header(reportId);
        List<CriterionView> criteria = jdbc.query("""
            SELECT c.criterion_id, c.code, c.name, c.descriptor, c.position,
                   r.response_id, r.selected_option_id, r.not_observed, r.observation_state,
                   r.observed_value, coalesce(r.version, 0) AS response_version
              FROM evaluation_criteria c LEFT JOIN evaluator_report_responses r
                ON r.criterion_id = c.criterion_id AND r.report_id = :reportId
             WHERE c.evaluation_template_version_id = :templateVersionId ORDER BY c.position
            """, Map.of("reportId", reportId, "templateVersionId", header.templateVersionId()), (rs, row) ->
                new CriterionView(rs.getObject("criterion_id", UUID.class), rs.getString("code"), rs.getString("name"),
                    rs.getString("descriptor"), rs.getInt("position"), List.of(), rs.getObject("response_id", UUID.class),
                    rs.getObject("selected_option_id", UUID.class), rs.getBoolean("not_observed"),
                    rs.getString("observation_state"), rs.getBigDecimal("observed_value"), rs.getLong("response_version")));
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

    private UUID processId(UUID groupId) {
        return jdbc.queryForObject("SELECT process_id FROM evaluation_groups WHERE group_id = :groupId",
            Map.of("groupId", groupId), UUID.class);
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
        if (List.of("SUBMITTED", "VALIDATED", "LOCKED", "COMPLETED").contains(header.status()))
            throw PrekinderDomainException.conflict("REPORT_LOCKED", "El informe está enviado o validado");
        if (!editable(header, Instant.now())) {
            throw PrekinderDomainException.forbidden("EDIT_WINDOW_CLOSED", "La ventana de edición está cerrada");
        }
    }

    private boolean editable(ReportHeader header, Instant now) {
        if (!List.of("PENDING", "IN_PROGRESS", "REOPENED", "RETURNED").contains(header.status())) return false;
        if (List.of("REOPENED", "RETURNED").contains(header.status())) return true;
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
                                boolean notObserved, String observationState,
                                BigDecimal observedValue, long responseVersion) {}
    public record NoteView(UUID noteId, String content, long version) {}
    public record ReportView(ReportHeader header, boolean editableNow, List<CriterionView> criteria, NoteView note) {}
}
