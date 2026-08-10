package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
public class PrekinderEvaluatorService {
    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "COORDINATOR", "CYCLE_DIRECTOR", "PK_ADMIN", "PK_COORDINATOR");
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final PrekinderReportService reports;
    private final EnvelopeEncryptionService encryption;
    private final ObjectMapper mapper;

    public PrekinderEvaluatorService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
        PrekinderAccessService access, PrekinderReportService reports,
        EnvelopeEncryptionService encryption, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.reports = reports;
        this.encryption = encryption;
        this.mapper = mapper;
    }

    public List<InstrumentView> instruments() {
        access.requireActor();
        return jdbc.query("""
            SELECT instrument_code, display_name, capture_mode, sensitive, active, position
              FROM evaluation_instruments WHERE active = true ORDER BY position
            """, Map.of(), (rs, row) -> new InstrumentView(rs.getString("instrument_code"),
            rs.getString("display_name"), rs.getString("capture_mode"), rs.getBoolean("sensitive"),
            rs.getBoolean("active"), rs.getInt("position")));
    }

    public EvaluatorAgenda agenda(UUID processId, LocalDate date, String requestedInstrument) {
        PrekinderActor actor = access.requireEvaluator();
        String instrument = normalizeInstrument(requestedInstrument);
        assertRoleInstrument(actor, instrument);
        LocalDate effectiveDate = date == null ? LocalDate.now(SANTIAGO) : date;
        Instant from = effectiveDate.atStartOfDay(SANTIAGO).toInstant();
        Instant to = effectiveDate.plusDays(1).atStartOfDay(SANTIAGO).toInstant();
        List<AssignmentView> assignments = jdbc.query("""
            SELECT ia.assignment_id, ia.instrument_code, ia.status AS assignment_status, ia.version AS assignment_version,
                   g.group_id, g.process_id, g.room_id, room.name AS room_name, g.stage, g.code,
                   g.starts_at, g.ends_at, g.capacity, g.required_evaluators, g.status AS group_status, g.version AS group_version
              FROM group_instrument_assignments ia
              JOIN evaluation_groups g ON g.group_id = ia.group_id
              JOIN prekinder_rooms room ON room.room_id = g.room_id
             WHERE ia.evaluator_id = :actorId AND ia.instrument_code = :instrument
               AND (:processId IS NULL OR g.process_id = :processId)
               AND g.starts_at >= :from AND g.starts_at < :to
               AND ia.status NOT IN ('REPLACED','CANCELLED')
             ORDER BY g.starts_at, g.code
            """, new MapSqlParameterSource().addValue("actorId", actor.id()).addValue("instrument", instrument)
            .addValue("processId", processId).addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)),
            (rs, row) -> {
                UUID assignmentId = rs.getObject("assignment_id", UUID.class);
                UUID groupId = rs.getObject("group_id", UUID.class);
                GroupView group = new GroupView(groupId, rs.getObject("process_id", UUID.class),
                    rs.getObject("room_id", UUID.class), rs.getString("room_name"), rs.getString("stage"),
                    rs.getString("code"), rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                    rs.getInt("capacity"), rs.getInt("required_evaluators"), rs.getString("group_status"),
                    rs.getLong("group_version"));
                return new AssignmentView(assignmentId, instrument, rs.getString("assignment_status"),
                    rs.getLong("assignment_version"), group, reportSummaries(assignmentId));
            });
        InstrumentView profile = instrument(instrument);
        return new EvaluatorAgenda(new Profile(actor.id(), profile.instrumentCode(), profile.displayName()), assignments);
    }

    public AssignmentView assign(UUID groupId, String requestedInstrument, UUID evaluatorId, UUID templateVersionId,
                                 String reason, long expectedVersion, UUID operationId) {
        PrekinderActor actor = access.requireAdmin();
        String instrument = normalizeInstrument(requestedInstrument);
        return transactions.execute(transaction -> {
            List<UUID> duplicate = jdbc.queryForList(
                "SELECT result_reference FROM processed_operations WHERE operation_id = :id",
                Map.of("id", operationId), UUID.class);
            if (!duplicate.isEmpty()) return assignment(duplicate.getFirst());
            AssignmentContext context = jdbc.queryForObject("""
                SELECT g.process_id, g.starts_at, g.ends_at, g.version,
                       p.active AS professional_active, tv.status AS template_status, t.type_code
                  FROM evaluation_groups g
                  JOIN professional_profiles p ON p.professional_id = :evaluatorId
                  JOIN evaluation_template_versions tv ON tv.evaluation_template_version_id = :templateVersionId
                  JOIN evaluation_templates t ON t.evaluation_template_id = tv.evaluation_template_id
                 WHERE g.group_id = :groupId AND t.process_id = g.process_id
                """, Map.of("groupId", groupId, "evaluatorId", evaluatorId, "templateVersionId", templateVersionId),
                (rs, row) -> new AssignmentContext(rs.getObject("process_id", UUID.class),
                    rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                    rs.getLong("version"), rs.getBoolean("professional_active"), rs.getString("template_status"),
                    normalizeInstrument(rs.getString("type_code"))));
            if (!context.professionalActive()) throw PrekinderDomainException.conflict("EVALUATOR_INACTIVE", "El profesional no está activo");
            if (!"PUBLISHED".equals(context.templateStatus()) || !instrument.equals(context.templateInstrument())) {
                throw PrekinderDomainException.conflict("TEMPLATE_NOT_PUBLISHED", "La pauta publicada no corresponde al instrumento");
            }
            if (expectedVersion != context.groupVersion()) throw new VersionConflictException("El grupo cambió");
            Long authorized = jdbc.queryForObject("""
                SELECT count(*) FROM professional_instrument_authorizations
                 WHERE process_id = :processId AND professional_id = :evaluatorId AND instrument_code = :instrument
                   AND active = true AND valid_from <= now() AND (valid_until IS NULL OR valid_until > now())
                """, Map.of("processId", context.processId(), "evaluatorId", evaluatorId, "instrument", instrument), Long.class);
            if (authorized == null || authorized == 0) {
                throw PrekinderDomainException.forbidden("INSTRUMENT_NOT_AUTHORIZED", "El profesional no está autorizado para este instrumento");
            }
            UUID assignmentId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO group_instrument_assignments(assignment_id, group_id, instrument_code, evaluator_id,
                    template_version_id, assigned_by)
                VALUES (:id, :groupId, :instrument, :evaluatorId, :templateVersionId, :actorId)
                """, Map.of("id", assignmentId, "groupId", groupId, "instrument", instrument,
                "evaluatorId", evaluatorId, "templateVersionId", templateVersionId, "actorId", actor.id()));
            for (UUID applicationId : jdbc.queryForList("""
                SELECT application_id FROM evaluation_group_members
                 WHERE group_id = :groupId AND status IN ('ASSIGNED','ATTENDED') ORDER BY assigned_at
                """, Map.of("groupId", groupId), UUID.class)) {
                jdbc.update("""
                    INSERT INTO evaluator_reports(report_id, group_id, application_id, evaluator_id,
                        evaluation_template_version_id, instrument_assignment_id, instrument_code)
                    VALUES (:id, :groupId, :applicationId, :evaluatorId, :templateVersionId, :assignmentId, :instrument)
                    """, Map.of("id", UUID.randomUUID(), "groupId", groupId, "applicationId", applicationId,
                    "evaluatorId", evaluatorId, "templateVersionId", templateVersionId,
                    "assignmentId", assignmentId, "instrument", instrument));
            }
            jdbc.update("""
                INSERT INTO processed_operations(operation_id, actor_id, operation_type, result_reference)
                VALUES (:operationId, :actorId, 'INSTRUMENT_ASSIGNED', :assignmentId)
                """, Map.of("operationId", operationId, "actorId", actor.id(), "assignmentId", assignmentId));
            audit(actor.id(), "INSTRUMENT_ASSIGNED", assignmentId,
                Map.of("groupId", groupId.toString(), "instrumentCode", instrument, "reason", safeReason(reason)));
            return assignment(assignmentId);
        });
    }

    public void authorize(UUID processId, UUID professionalId, String requestedInstrument, UUID operationId) {
        PrekinderActor actor = access.requireAdmin();
        String instrument = normalizeInstrument(requestedInstrument);
        transactions.executeWithoutResult(transaction -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO professional_instrument_authorizations(authorization_id, process_id, professional_id,
                    instrument_code, authorized_by)
                VALUES (:id, :processId, :professionalId, :instrument, :actorId)
                ON CONFLICT (process_id, professional_id, instrument_code) WHERE active DO NOTHING
                """, Map.of("id", id, "processId", processId, "professionalId", professionalId,
                "instrument", instrument, "actorId", actor.id()));
            jdbc.update("""
                INSERT INTO processed_operations(operation_id, actor_id, operation_type, result_reference)
                VALUES (:operationId, :actorId, 'INSTRUMENT_AUTHORIZED', :professionalId)
                ON CONFLICT (operation_id) DO NOTHING
                """, Map.of("operationId", operationId, "actorId", actor.id(), "professionalId", professionalId));
        });
    }

    public AssignmentView transition(UUID assignmentId, String transition, long expectedVersion, UUID operationId) {
        PrekinderActor actor = access.requireEvaluator();
        String target = switch (transition) {
            case "confirm" -> "CONFIRMED";
            case "start" -> "IN_PROGRESS";
            case "submit" -> "SUBMITTED";
            default -> throw new IllegalArgumentException("Transición inválida");
        };
        return transactions.execute(transaction -> {
            int updated = jdbc.update("""
                UPDATE group_instrument_assignments
                   SET status = :target, version = version + 1,
                       confirmed_at = CASE WHEN :target = 'CONFIRMED' THEN now() ELSE confirmed_at END,
                       started_at = CASE WHEN :target = 'IN_PROGRESS' THEN now() ELSE started_at END,
                       ended_at = CASE WHEN :target = 'SUBMITTED' THEN now() ELSE ended_at END
                 WHERE assignment_id = :id AND evaluator_id = :actorId AND version = :version
                   AND status NOT IN ('REPLACED','CANCELLED','COMPLETED')
                """, Map.of("target", target, "id", assignmentId, "actorId", actor.id(), "version", expectedVersion));
            if (updated != 1) throw new VersionConflictException("La asignación cambió");
            if ("IN_PROGRESS".equals(target)) {
                jdbc.update("""
                    UPDATE evaluator_reports SET status = 'IN_PROGRESS', version = version + 1, updated_at = now()
                     WHERE instrument_assignment_id = :id AND status = 'PENDING'
                    """, Map.of("id", assignmentId));
            }
            if ("SUBMITTED".equals(target)) {
                Long incomplete = jdbc.queryForObject("""
                    SELECT count(*) FROM evaluator_reports
                     WHERE instrument_assignment_id = :id AND status NOT IN ('COMPLETED','SUBMITTED','VALIDATED','LOCKED')
                    """, Map.of("id", assignmentId), Long.class);
                if (incomplete != null && incomplete > 0) {
                    throw PrekinderDomainException.conflict("REPORT_INCOMPLETE", "Completa todos los informes antes de enviar");
                }
                jdbc.update("""
                    UPDATE evaluator_reports SET status = 'SUBMITTED', submitted_at = now(),
                        version = version + 1, updated_at = now()
                     WHERE instrument_assignment_id = :id AND status = 'COMPLETED'
                    """, Map.of("id", assignmentId));
            }
            jdbc.update("""
                INSERT INTO processed_operations(operation_id, actor_id, operation_type, result_reference)
                VALUES (:operationId, :actorId, :operationType, :assignmentId)
                ON CONFLICT (operation_id) DO NOTHING
                """, Map.of("operationId", operationId, "actorId", actor.id(), "operationType",
                "EVALUATION_" + target, "assignmentId", assignmentId));
            return assignment(assignmentId);
        });
    }

    public CaptureView capture(UUID assignmentId) {
        PrekinderActor actor = access.requireEvaluator();
        AssignmentView assignment = assignment(assignmentId);
        if (!ADMIN_ROLES.contains(actor.role()) && !assignmentEvaluator(assignmentId).equals(actor.id())) {
            throw PrekinderDomainException.forbidden("NOT_ASSIGNED", "La evaluación no está asignada a este profesional");
        }
        List<PrekinderReportService.ReportView> reportDetails = assignment.reports().stream()
            .map(summary -> reports.report(summary.reportId())).toList();
        return new CaptureView(assignment, reportDetails);
    }

    private AssignmentView assignment(UUID assignmentId) {
        return jdbc.queryForObject("""
            SELECT ia.assignment_id, ia.instrument_code, ia.status, ia.version,
                   g.group_id, g.process_id, g.room_id, room.name AS room_name, g.stage, g.code,
                   g.starts_at, g.ends_at, g.capacity, g.required_evaluators, g.status AS group_status, g.version AS group_version
              FROM group_instrument_assignments ia JOIN evaluation_groups g ON g.group_id = ia.group_id
              JOIN prekinder_rooms room ON room.room_id = g.room_id WHERE ia.assignment_id = :id
            """, Map.of("id", assignmentId), (rs, row) -> new AssignmentView(assignmentId,
            rs.getString("instrument_code"), rs.getString("status"), rs.getLong("version"),
            new GroupView(rs.getObject("group_id", UUID.class), rs.getObject("process_id", UUID.class),
                rs.getObject("room_id", UUID.class), rs.getString("room_name"), rs.getString("stage"),
                rs.getString("code"), rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                rs.getInt("capacity"), rs.getInt("required_evaluators"), rs.getString("group_status"),
                rs.getLong("group_version")), reportSummaries(assignmentId)));
    }

    private List<ReportSummary> reportSummaries(UUID assignmentId) {
        return jdbc.query("""
            SELECT r.report_id, r.application_id, r.status, r.version, r.raw_score, r.maximum_score,
                   a.applicant_id, ap.identity_ciphertext, ap.identity_iv, ap.identity_wrapped_dek,
                   ap.identity_wrapped_dek_iv, ap.identity_key_version
              FROM evaluator_reports r JOIN applications a ON a.application_id = r.application_id
              JOIN applicants ap ON ap.applicant_id = a.applicant_id
             WHERE r.instrument_assignment_id = :id ORDER BY r.created_at
            """, Map.of("id", assignmentId), (rs, row) -> {
                UUID applicationId = rs.getObject("application_id", UUID.class);
                UUID applicantId = rs.getObject("applicant_id", UUID.class);
                String name = applicantName(applicationId, applicantId, new EncryptedPayload(
                    rs.getString("identity_ciphertext"), rs.getString("identity_iv"),
                    rs.getString("identity_wrapped_dek"), rs.getString("identity_wrapped_dek_iv"),
                    rs.getString("identity_key_version")));
                return new ReportSummary(rs.getObject("report_id", UUID.class), applicationId, name,
                    rs.getString("status"), rs.getLong("version"), rs.getBigDecimal("raw_score"),
                    rs.getBigDecimal("maximum_score"));
            });
    }

    private String applicantName(UUID applicationId, UUID applicantId, EncryptedPayload identity) {
        try {
            var node = mapper.readTree(encryption.decrypt(identity,
                "prekinder|applicants|" + applicantId + "|application:" + applicationId + "|identity"));
            return (node.path("firstName").asText() + " " + node.path("paternalLastName").asText()
                + " " + node.path("maternalLastName").asText()).trim().replaceAll("\\s+", " ");
        } catch (Exception exception) {
            return "Postulante";
        }
    }

    private UUID assignmentEvaluator(UUID assignmentId) {
        return jdbc.queryForObject("SELECT evaluator_id FROM group_instrument_assignments WHERE assignment_id = :id",
            Map.of("id", assignmentId), UUID.class);
    }

    private InstrumentView instrument(String code) {
        return jdbc.queryForObject("""
            SELECT instrument_code, display_name, capture_mode, sensitive, active, position
              FROM evaluation_instruments WHERE instrument_code = :code AND active = true
            """, Map.of("code", code), (rs, row) -> new InstrumentView(rs.getString("instrument_code"),
            rs.getString("display_name"), rs.getString("capture_mode"), rs.getBoolean("sensitive"),
            rs.getBoolean("active"), rs.getInt("position")));
    }

    static String normalizeInstrument(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Instrumento obligatorio");
        return switch (value.trim().toUpperCase()) {
            case "INDICATORS", "PREKINDER_INDICATORS", "PK_EVALUATOR_ENTRY_INDICATORS" -> "ENTRY_INDICATORS";
            case "SUPPORT", "PREKINDER_SUPPORT", "PK_EVALUATOR_LEARNING_SUPPORT" -> "LEARNING_SUPPORT";
            case "PREKINDER_ACADEMIC", "PK_EVALUATOR_ACADEMIC" -> "ACADEMIC";
            case "PREKINDER_PSYCHOMOTOR", "PK_EVALUATOR_PSYCHOMOTOR" -> "PSYCHOMOTOR";
            case "PREKINDER_PSYCHOLOGY", "PK_EVALUATOR_PSYCHOLOGY" -> "PSYCHOLOGY";
            case "PREKINDER_OBSERVER", "PK_EVALUATOR_GROUP_OBSERVATION" -> "GROUP_OBSERVATION";
            case "PREKINDER_DAP", "PK_EVALUATOR_DAP" -> "DAP";
            default -> value.trim().toUpperCase();
        };
    }

    private static void assertRoleInstrument(PrekinderActor actor, String instrument) {
        if (ADMIN_ROLES.contains(actor.role())
            || Set.of("TEACHER", "PSYCHOLOGIST", "INTERVIEWER", "EVALUATOR").contains(actor.role())) return;
        String roleInstrument = normalizeInstrument(actor.role());
        if (!instrument.equals(roleInstrument)) {
            throw PrekinderDomainException.forbidden("INSTRUMENT_NOT_AUTHORIZED", "El instrumento no corresponde al perfil autenticado");
        }
    }

    private void audit(UUID actorId, String action, UUID aggregateId, Map<String, ?> metadata) {
        String json;
        try { json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata); }
        catch (Exception ignored) { json = "{}"; }
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result, metadata)
            VALUES (:id, :actorId, :action, 'INSTRUMENT_ASSIGNMENT', :aggregateId, 'SUCCESS', CAST(:metadata AS jsonb))
            """, Map.of("id", UUID.randomUUID(), "actorId", actorId, "action", action,
                "aggregateId", aggregateId, "metadata", json));
    }
    private static String safeReason(String reason) { return reason == null ? "" : reason.trim(); }

    private record AssignmentContext(UUID processId, Instant startsAt, Instant endsAt, long groupVersion,
                                     boolean professionalActive, String templateStatus, String templateInstrument) {}
    public record InstrumentView(String instrumentCode, String displayName, String captureMode,
                                 boolean sensitive, boolean active, int position) {}
    public record Profile(UUID actorId, String instrumentCode, String instrumentName) {}
    public record EvaluatorAgenda(Profile profile, List<AssignmentView> assignments) {}
    public record AssignmentView(UUID assignmentId, String instrumentCode, String status, long version,
                                 GroupView group, List<ReportSummary> reports) {}
    public record GroupView(UUID groupId, UUID processId, UUID roomId, String roomName, String stage, String code,
                            Instant startsAt, Instant endsAt, int capacity, int requiredEvaluators,
                            String status, long version) {}
    public record ReportSummary(UUID reportId, UUID applicationId, String applicantName, String status, long version,
                                java.math.BigDecimal rawScore, java.math.BigDecimal maximumScore) {}
    public record CaptureView(AssignmentView assignment, List<PrekinderReportService.ReportView> reports) {}
}
