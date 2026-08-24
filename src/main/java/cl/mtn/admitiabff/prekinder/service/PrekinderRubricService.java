package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.realtime.PrekinderRealtimeNotifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
public class PrekinderRubricService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final PrekinderRealtimeNotifier realtime;

    public PrekinderRubricService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
        PrekinderAccessService access, PrekinderRealtimeNotifier realtime) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.realtime = realtime;
    }

    public List<RubricSummary> catalog() {
        access.requireAdmin();
        return jdbc.query("""
            SELECT template.evaluation_template_id, template.process_id, template.type_code,
                   template.name, template.status, template.version,
                   count(version.evaluation_template_version_id) AS version_count,
                   max(version.version) AS latest_version,
                   bool_or(version.status = 'PUBLISHED') AS has_published
              FROM evaluation_templates template
              LEFT JOIN evaluation_template_versions version
                ON version.evaluation_template_id = template.evaluation_template_id
             GROUP BY template.evaluation_template_id
             ORDER BY template.status, template.name
            """, Map.of(), (rs, row) -> new RubricSummary(
                rs.getObject("evaluation_template_id", UUID.class),
                rs.getObject("process_id", UUID.class), rs.getString("type_code"),
                rs.getString("name"), rs.getString("status"), rs.getLong("version"),
                rs.getInt("version_count"), rs.getInt("latest_version"), rs.getBoolean("has_published")));
    }

    public RubricDetail detail(UUID rubricId) {
        access.requireAdmin();
        RubricSummary summary = summary(rubricId);
        List<VersionSummary> versions = jdbc.query("""
            SELECT evaluation_template_version_id, version, status, name, instrument_code,
                   maximum_score, published_at,
                   (SELECT count(*) FROM evaluation_criteria criterion
                     WHERE criterion.evaluation_template_version_id = version.evaluation_template_version_id) AS criteria_count
              FROM evaluation_template_versions version
             WHERE evaluation_template_id = :id ORDER BY version DESC
            """, Map.of("id", rubricId), (rs, row) -> new VersionSummary(
                rs.getObject("evaluation_template_version_id", UUID.class), rs.getInt("version"),
                rs.getString("status"), rs.getString("name"), rs.getString("instrument_code"),
                rs.getBigDecimal("maximum_score"),
                instant(rs.getTimestamp("published_at")), rs.getInt("criteria_count")));
        return new RubricDetail(summary, versions);
    }

    public RubricVersionView version(UUID versionId) {
        access.requireAdmin();
        return loadVersion(versionId);
    }

    public RubricVersionView create(CreateRubric command) {
        PrekinderActor actor = access.requireAdmin();
        String code = instrument(command.instrumentCode());
        UUID rubricId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        return transactions.execute(status -> {
            try {
                jdbc.update("""
                    INSERT INTO evaluation_templates(evaluation_template_id, process_id, type_code, name)
                    VALUES (:id, NULL, :code, :name)
                    """, Map.of("id", rubricId, "code", code, "name", required(command.name())));
            } catch (DataIntegrityViolationException exception) {
                throw PrekinderDomainException.conflict("RUBRIC_CODE_TAKEN",
                    "Ya existe una pauta activa para ese instrumento");
            }
            jdbc.update("""
                INSERT INTO evaluation_template_versions(evaluation_template_version_id,
                    evaluation_template_id, version, status, name, instrument_code)
                VALUES (:id, :rubricId, 1, 'DRAFT', :name, :code)
                """, Map.of("id", versionId, "rubricId", rubricId,
                    "name", required(command.name()), "code", code));
            audit(actor.id(), "RUBRIC_CREATED", rubricId);
            return loadVersion(versionId);
        });
    }

    public RubricVersionView saveDraft(UUID versionId, DraftCommand command) {
        PrekinderActor actor = access.requireAdmin();
        validateDraft(command);
        return transactions.execute(status -> {
            Map<String, Object> current = jdbc.queryForMap("""
                SELECT version.evaluation_template_id, version.status, template.version AS template_version
                  FROM evaluation_template_versions version
                  JOIN evaluation_templates template
                    ON template.evaluation_template_id = version.evaluation_template_id
                 WHERE version.evaluation_template_version_id = :id FOR UPDATE
                """, Map.of("id", versionId));
            if (!"DRAFT".equals(current.get("status"))) {
                throw PrekinderDomainException.conflict("RUBRIC_VERSION_IMMUTABLE",
                    "Sólo las versiones borrador se pueden editar");
            }
            long templateVersion = ((Number) current.get("template_version")).longValue();
            if (templateVersion != command.expectedRubricVersion()) {
                throw new VersionConflictException("La pauta cambió");
            }
            UUID rubricId = (UUID) current.get("evaluation_template_id");
            String instrumentCode = instrument(command.instrumentCode());
            jdbc.update("""
                UPDATE evaluation_templates SET name = :name, type_code = :instrumentCode,
                       version = version + 1, updated_at = now()
                 WHERE evaluation_template_id = :id AND version = :version
                """, Map.of("id", rubricId, "name", required(command.name()),
                    "instrumentCode", instrumentCode, "version", templateVersion));
            jdbc.update("DELETE FROM evaluation_options WHERE criterion_id IN (SELECT criterion_id FROM evaluation_criteria WHERE evaluation_template_version_id = :id)",
                Map.of("id", versionId));
            jdbc.update("DELETE FROM evaluation_criteria WHERE evaluation_template_version_id = :id", Map.of("id", versionId));

            BigDecimal maximum = BigDecimal.ZERO;
            for (int criterionIndex = 0; criterionIndex < command.criteria().size(); criterionIndex++) {
                CriterionCommand criterion = command.criteria().get(criterionIndex);
                UUID criterionId = UUID.randomUUID();
                jdbc.update("""
                    INSERT INTO evaluation_criteria(criterion_id, evaluation_template_version_id,
                        code, name, descriptor, position, required)
                    VALUES (:id, :versionId, :code, :name, :descriptor, :position, :required)
                    """, new MapSqlParameterSource().addValue("id", criterionId).addValue("versionId", versionId)
                    .addValue("code", required(criterion.code()).toUpperCase()).addValue("name", required(criterion.name()))
                    .addValue("descriptor", required(criterion.descriptor())).addValue("position", criterionIndex)
                    .addValue("required", criterion.required()));
                BigDecimal criterionMaximum = BigDecimal.ZERO;
                for (int optionIndex = 0; optionIndex < criterion.options().size(); optionIndex++) {
                    OptionCommand option = criterion.options().get(optionIndex);
                    criterionMaximum = criterionMaximum.max(option.value());
                    jdbc.update("""
                        INSERT INTO evaluation_options(option_id, criterion_id, value, label,
                            descriptor, professionally_validated, position)
                        VALUES (:id, :criterionId, :value, :label, :descriptor, :validated, :position)
                        """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                        .addValue("criterionId", criterionId).addValue("value", option.value())
                        .addValue("label", required(option.label())).addValue("descriptor", required(option.descriptor()))
                        .addValue("validated", option.professionallyValidated()).addValue("position", optionIndex));
                }
                maximum = maximum.add(criterionMaximum);
            }
            jdbc.update("""
                UPDATE evaluation_template_versions
                   SET name = :name, instrument_code = :instrumentCode, maximum_score = :maximum
                 WHERE evaluation_template_version_id = :id AND status = 'DRAFT'
                """, Map.of("id", versionId, "name", required(command.name()),
                    "instrumentCode", instrumentCode, "maximum", maximum));
            audit(actor.id(), "RUBRIC_DRAFT_SAVED", versionId);
            return loadVersion(versionId);
        });
    }

    public RubricVersionView duplicate(UUID rubricId) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            long drafts = count("""
                SELECT count(*) FROM evaluation_template_versions
                 WHERE evaluation_template_id = :id AND status = 'DRAFT'
                """, rubricId);
            if (drafts > 0) {
                throw PrekinderDomainException.conflict("RUBRIC_DRAFT_EXISTS",
                    "Edita o elimina el borrador existente antes de crear otra versión");
            }
            UUID sourceId = jdbc.queryForObject("""
                SELECT evaluation_template_version_id FROM evaluation_template_versions
                 WHERE evaluation_template_id = :id
                 ORDER BY CASE status WHEN 'PUBLISHED' THEN 0 ELSE 1 END, version DESC LIMIT 1
                """, Map.of("id", rubricId), UUID.class);
            int nextVersion = jdbc.queryForObject("""
                SELECT coalesce(max(version), 0) + 1 FROM evaluation_template_versions
                 WHERE evaluation_template_id = :id
                """, Map.of("id", rubricId), Integer.class);
            UUID targetId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO evaluation_template_versions(evaluation_template_version_id,
                    evaluation_template_id, version, status, maximum_score, name, instrument_code)
                SELECT :targetId, evaluation_template_id, :nextVersion, 'DRAFT', maximum_score,
                       name, instrument_code
                  FROM evaluation_template_versions WHERE evaluation_template_version_id = :sourceId
                """, Map.of("targetId", targetId, "nextVersion", nextVersion, "sourceId", sourceId));
            List<Map<String, Object>> criteria = jdbc.queryForList("""
                SELECT criterion_id, code, name, descriptor, position, required
                  FROM evaluation_criteria WHERE evaluation_template_version_id = :id ORDER BY position
                """, Map.of("id", sourceId));
            for (Map<String, Object> criterion : criteria) {
                UUID newCriterionId = UUID.randomUUID();
                jdbc.update("""
                    INSERT INTO evaluation_criteria(criterion_id, evaluation_template_version_id,
                        code, name, descriptor, position, required)
                    VALUES (:id, :versionId, :code, :name, :descriptor, :position, :required)
                    """, new MapSqlParameterSource().addValue("id", newCriterionId).addValue("versionId", targetId)
                    .addValue("code", criterion.get("code")).addValue("name", criterion.get("name"))
                    .addValue("descriptor", criterion.get("descriptor")).addValue("position", criterion.get("position"))
                    .addValue("required", criterion.get("required")));
                jdbc.update("""
                    INSERT INTO evaluation_options(option_id, criterion_id, value, label,
                        descriptor, professionally_validated, position)
                    SELECT gen_random_uuid(), :targetCriterionId, value, label, descriptor,
                           professionally_validated, position
                      FROM evaluation_options WHERE criterion_id = :sourceCriterionId
                    """, Map.of("targetCriterionId", newCriterionId,
                    "sourceCriterionId", criterion.get("criterion_id")));
            }
            audit(actor.id(), "RUBRIC_VERSION_DUPLICATED", targetId);
            return loadVersion(targetId);
        });
    }

    public RubricVersionView publish(UUID versionId) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            RubricVersionView draft = loadVersion(versionId);
            if (!"DRAFT".equals(draft.status())) {
                throw PrekinderDomainException.conflict("RUBRIC_VERSION_IMMUTABLE", "La versión ya no es borrador");
            }
            validatePublishedContent(draft);
            jdbc.update("""
                UPDATE evaluation_template_versions SET status = 'SUPERSEDED'
                 WHERE evaluation_template_id = :rubricId AND status = 'PUBLISHED'
                """, Map.of("rubricId", draft.rubricId()));
            jdbc.update("""
                UPDATE evaluation_template_versions SET status = 'PUBLISHED', published_at = now()
                 WHERE evaluation_template_version_id = :id AND status = 'DRAFT'
                """, Map.of("id", versionId));
            audit(actor.id(), "RUBRIC_VERSION_PUBLISHED", versionId);
            return loadVersion(versionId);
        });
    }

    public void deleteDraft(UUID versionId) {
        PrekinderActor actor = access.requireAdmin();
        transactions.executeWithoutResult(status -> {
            Map<String, Object> version = jdbc.queryForMap("""
                SELECT evaluation_template_id, status FROM evaluation_template_versions
                 WHERE evaluation_template_version_id = :id FOR UPDATE
                """, Map.of("id", versionId));
            if (!"DRAFT".equals(version.get("status"))) {
                throw PrekinderDomainException.conflict("RUBRIC_VERSION_IN_USE",
                    "Sólo se pueden eliminar versiones borrador");
            }
            long references = count("""
                SELECT count(*) FROM process_rubric_assignments
                 WHERE evaluation_template_version_id = :id
                """, versionId);
            if (references > 0) throw PrekinderDomainException.conflict("RUBRIC_VERSION_IN_USE",
                "La versión está asociada a un proceso");
            jdbc.update("DELETE FROM evaluation_options WHERE criterion_id IN (SELECT criterion_id FROM evaluation_criteria WHERE evaluation_template_version_id = :id)", Map.of("id", versionId));
            jdbc.update("DELETE FROM evaluation_criteria WHERE evaluation_template_version_id = :id", Map.of("id", versionId));
            jdbc.update("DELETE FROM evaluation_template_versions WHERE evaluation_template_version_id = :id", Map.of("id", versionId));
            UUID rubricId = (UUID) version.get("evaluation_template_id");
            long remaining = count("SELECT count(*) FROM evaluation_template_versions WHERE evaluation_template_id = :id", rubricId);
            if (remaining == 0) jdbc.update("DELETE FROM evaluation_templates WHERE evaluation_template_id = :id", Map.of("id", rubricId));
            audit(actor.id(), "RUBRIC_DRAFT_DELETED", versionId);
        });
    }

    public RubricSummary archive(UUID rubricId, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        int updated = jdbc.update("""
            UPDATE evaluation_templates SET status = 'ARCHIVED', version = version + 1, updated_at = now()
             WHERE evaluation_template_id = :id AND version = :version AND status = 'ACTIVE'
            """, Map.of("id", rubricId, "version", expectedVersion));
        if (updated != 1) throw new VersionConflictException("La pauta cambió");
        audit(actor.id(), "RUBRIC_ARCHIVED", rubricId);
        return summary(rubricId);
    }

    public List<AssignmentView> assignments(UUID processId) {
        access.requireAdmin();
        return jdbc.query("""
            SELECT assignment.assignment_id, assignment.process_id, assignment.instrument_code,
                   assignment.evaluation_template_version_id, assignment.version,
                   template.evaluation_template_id, template.name, version.version AS rubric_version,
                   version.maximum_score
              FROM process_rubric_assignments assignment
              JOIN evaluation_template_versions version
                ON version.evaluation_template_version_id = assignment.evaluation_template_version_id
              JOIN evaluation_templates template
                ON template.evaluation_template_id = version.evaluation_template_id
             WHERE assignment.process_id = :id AND assignment.active
             ORDER BY assignment.instrument_code
            """, Map.of("id", processId), (rs, row) -> new AssignmentView(
                rs.getObject("assignment_id", UUID.class), rs.getObject("process_id", UUID.class),
                rs.getString("instrument_code"), rs.getObject("evaluation_template_id", UUID.class),
                rs.getObject("evaluation_template_version_id", UUID.class), rs.getString("name"),
                rs.getInt("rubric_version"), rs.getBigDecimal("maximum_score"), rs.getLong("version")));
    }

    public AssignmentView assign(UUID processId, String instrumentCode, UUID versionId, Long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        String instrument = instrument(instrumentCode);
        return transactions.execute(status -> {
            Map<String, Object> version = jdbc.queryForMap("""
                SELECT template.evaluation_template_id, template.status AS template_status,
                       version.status AS version_status, version.instrument_code
                  FROM evaluation_template_versions version
                  JOIN evaluation_templates template
                    ON template.evaluation_template_id = version.evaluation_template_id
                 WHERE version.evaluation_template_version_id = :id
                """, Map.of("id", versionId));
            if (!"ACTIVE".equals(version.get("template_status")) || !"PUBLISHED".equals(version.get("version_status"))) {
                throw PrekinderDomainException.conflict("RUBRIC_NOT_PUBLISHED",
                    "Asocia una versión publicada de una pauta activa");
            }
            if (!instrument.equals(version.get("instrument_code"))) {
                throw new IllegalArgumentException("La pauta no corresponde al instrumento seleccionado");
            }
            List<AssignmentView> current = assignments(processId).stream()
                .filter(value -> value.instrumentCode().equals(instrument)).toList();
            if (!current.isEmpty()) {
                AssignmentView active = current.getFirst();
                if (expectedVersion == null || active.version() != expectedVersion) {
                    throw new VersionConflictException("La asociación de pauta cambió");
                }
                jdbc.update("""
                    UPDATE process_rubric_assignments SET active = false, valid_until = now(), version = version + 1
                     WHERE assignment_id = :id AND version = :version
                    """, Map.of("id", active.assignmentId(), "version", expectedVersion));
            }
            UUID assignmentId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO process_rubric_assignments(assignment_id, process_id, instrument_code,
                    evaluation_template_version_id, assigned_by)
                VALUES (:id, :processId, :instrument, :versionId, :actorId)
                """, Map.of("id", assignmentId, "processId", processId, "instrument", instrument,
                "versionId", versionId, "actorId", actor.id()));
            List<UUID> affectedEvaluators = jdbc.queryForList("""
                SELECT DISTINCT group_assignment.evaluator_id
                  FROM group_instrument_assignments group_assignment
                  JOIN evaluation_groups evaluation_group
                    ON evaluation_group.group_id = group_assignment.group_id
                 WHERE evaluation_group.process_id = :processId
                   AND group_assignment.instrument_code = :instrument
                   AND group_assignment.status NOT IN ('REPLACED','CANCELLED','COMPLETED')
                """, Map.of("processId", processId, "instrument", instrument), UUID.class);
            jdbc.update("""
                UPDATE group_instrument_assignments group_assignment
                   SET template_version_id = :versionId, version = version + 1
                  FROM evaluation_groups evaluation_group
                 WHERE evaluation_group.group_id = group_assignment.group_id
                   AND evaluation_group.process_id = :processId
                   AND group_assignment.instrument_code = :instrument
                   AND group_assignment.status NOT IN ('REPLACED','CANCELLED','COMPLETED')
                """, Map.of("processId", processId, "instrument", instrument, "versionId", versionId));
            int migratedReports = jdbc.update("""
                UPDATE evaluator_reports report
                   SET evaluation_template_version_id = :versionId,
                       raw_score = NULL, maximum_score = NULL,
                       version = version + 1, updated_at = now()
                  FROM evaluation_groups evaluation_group
                 WHERE evaluation_group.group_id = report.group_id
                   AND evaluation_group.process_id = :processId
                   AND report.instrument_code = :instrument
                   AND report.evaluation_template_version_id <> :versionId
                   AND report.status = 'PENDING'
                   AND NOT EXISTS (
                       SELECT 1 FROM evaluator_report_responses response
                        WHERE response.report_id = report.report_id
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM evaluator_report_notes note
                        WHERE note.report_id = report.report_id
                   )
                """, Map.of("processId", processId, "instrument", instrument, "versionId", versionId));
            Long preservedValue = jdbc.queryForObject("""
                SELECT count(*)
                  FROM evaluator_reports report
                  JOIN evaluation_groups evaluation_group ON evaluation_group.group_id = report.group_id
                 WHERE evaluation_group.process_id = :processId
                   AND report.instrument_code = :instrument
                   AND report.evaluation_template_version_id <> :versionId
                """, Map.of("processId", processId, "instrument", instrument, "versionId", versionId), Long.class);
            long preservedReports = preservedValue == null ? 0 : preservedValue;
            auditAssignment(actor.id(), assignmentId, processId, instrument, migratedReports, preservedReports);
            affectedEvaluators.forEach(evaluatorId ->
                realtime.notifyAfterCommit(evaluatorId, processId, "EVALUATOR_RUBRIC_CHANGED"));
            return assignments(processId).stream().filter(value -> value.assignmentId().equals(assignmentId)).findFirst().orElseThrow();
        });
    }

    private RubricSummary summary(UUID rubricId) {
        return jdbc.queryForObject("""
            SELECT template.evaluation_template_id, template.process_id, template.type_code,
                   template.name, template.status, template.version,
                   count(version.evaluation_template_version_id) AS version_count,
                   coalesce(max(version.version), 0) AS latest_version,
                   coalesce(bool_or(version.status = 'PUBLISHED'), false) AS has_published
              FROM evaluation_templates template
              LEFT JOIN evaluation_template_versions version
                ON version.evaluation_template_id = template.evaluation_template_id
             WHERE template.evaluation_template_id = :id
             GROUP BY template.evaluation_template_id
            """, Map.of("id", rubricId), (rs, row) -> new RubricSummary(
                rs.getObject("evaluation_template_id", UUID.class), rs.getObject("process_id", UUID.class),
                rs.getString("type_code"), rs.getString("name"), rs.getString("status"),
                rs.getLong("version"), rs.getInt("version_count"), rs.getInt("latest_version"),
                rs.getBoolean("has_published")));
    }

    private RubricVersionView loadVersion(UUID versionId) {
        Map<String, Object> header = jdbc.queryForMap("""
            SELECT version.evaluation_template_version_id, version.evaluation_template_id,
                   version.version, version.status, version.maximum_score, version.published_at,
                   version.name, version.instrument_code, template.version AS rubric_revision
              FROM evaluation_template_versions version
              JOIN evaluation_templates template
                ON template.evaluation_template_id = version.evaluation_template_id
             WHERE version.evaluation_template_version_id = :id
            """, Map.of("id", versionId));
        List<CriterionView> criteria = jdbc.query("""
            SELECT criterion_id, code, name, descriptor, position, required
              FROM evaluation_criteria WHERE evaluation_template_version_id = :id ORDER BY position
            """, Map.of("id", versionId), (rs, row) -> {
                UUID criterionId = rs.getObject("criterion_id", UUID.class);
                List<OptionView> options = jdbc.query("""
                    SELECT option_id, value, label, descriptor, professionally_validated, position
                      FROM evaluation_options WHERE criterion_id = :id ORDER BY position
                    """, Map.of("id", criterionId), (ors, optionRow) -> new OptionView(
                        ors.getObject("option_id", UUID.class), ors.getBigDecimal("value"),
                        ors.getString("label"), ors.getString("descriptor"),
                        ors.getBoolean("professionally_validated"), ors.getInt("position")));
                return new CriterionView(criterionId, rs.getString("code"), rs.getString("name"),
                    rs.getString("descriptor"), rs.getInt("position"), rs.getBoolean("required"), options);
            });
        return new RubricVersionView((UUID) header.get("evaluation_template_version_id"),
            (UUID) header.get("evaluation_template_id"), String.valueOf(header.get("name")),
            String.valueOf(header.get("instrument_code")), ((Number) header.get("version")).intValue(),
            String.valueOf(header.get("status")), (BigDecimal) header.get("maximum_score"),
            instant((java.sql.Timestamp) header.get("published_at")),
            ((Number) header.get("rubric_revision")).longValue(), criteria);
    }

    static void validateDraft(DraftCommand command) {
        required(command.name());
        if (command.criteria() == null || command.criteria().isEmpty()) {
            throw new IllegalArgumentException("Agrega al menos un criterio");
        }
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (CriterionCommand criterion : command.criteria()) {
            String code = required(criterion.code()).toUpperCase();
            if (!codes.add(code)) throw new IllegalArgumentException("Los códigos de criterio no pueden repetirse");
            required(criterion.name());
            required(criterion.descriptor());
            if (criterion.options() == null || criterion.options().size() < 2) {
                throw new IllegalArgumentException("Cada criterio necesita al menos dos opciones");
            }
            BigDecimal previous = null;
            for (OptionCommand option : criterion.options()) {
                if (option.value() == null || option.value().compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Los puntajes no pueden ser negativos");
                }
                if (previous != null && option.value().compareTo(previous) <= 0) {
                    throw new IllegalArgumentException("Ordena las opciones por puntaje ascendente sin duplicados");
                }
                previous = option.value();
                required(option.label());
                required(option.descriptor());
            }
        }
    }

    private static void validatePublishedContent(RubricVersionView draft) {
        if (draft.criteria().isEmpty() || draft.maximumScore() == null
            || draft.maximumScore().compareTo(BigDecimal.ZERO) <= 0) {
            throw PrekinderDomainException.conflict("RUBRIC_INCOMPLETE",
                "Completa criterios, opciones y puntajes antes de publicar");
        }
    }

    private long count(String sql, UUID id) {
        Long value = jdbc.queryForObject(sql, Map.of("id", id), Long.class);
        return value == null ? 0 : value;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Completa los campos obligatorios");
        return value.trim();
    }

    private static String instrument(String value) {
        String normalized = required(value).trim().toUpperCase();
        if (!PrekinderProcessLifecycleService.REQUIRED_INSTRUMENTS.contains(normalized)) {
            throw new IllegalArgumentException("Instrumento de pauta inválido");
        }
        return normalized;
    }

    private void audit(UUID actorId, String action, UUID aggregateId) {
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result)
            VALUES (:id, :actorId, :action, 'RUBRIC', :aggregateId, 'SUCCESS')
            """, Map.of("id", UUID.randomUUID(), "actorId", actorId,
                "action", action, "aggregateId", aggregateId));
    }

    private void auditAssignment(UUID actorId, UUID assignmentId, UUID processId, String instrument,
                                 int migratedReports, long preservedReports) {
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result, metadata)
            VALUES (:id, :actorId, 'PROCESS_RUBRIC_ASSIGNED', 'RUBRIC', :assignmentId, 'SUCCESS',
                    jsonb_build_object('processId', CAST(:processId AS text),
                                       'instrumentCode', :instrument,
                                       'migratedReports', :migratedReports,
                                       'preservedReports', :preservedReports))
            """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("actorId", actorId)
            .addValue("assignmentId", assignmentId).addValue("processId", processId)
            .addValue("instrument", instrument).addValue("migratedReports", migratedReports)
            .addValue("preservedReports", preservedReports));
    }

    private static Instant instant(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }

    public record CreateRubric(String name, String instrumentCode) {}
    public record DraftCommand(String name, String instrumentCode, long expectedRubricVersion,
        List<CriterionCommand> criteria) {}
    public record CriterionCommand(String code, String name, String descriptor, boolean required,
        List<OptionCommand> options) {}
    public record OptionCommand(BigDecimal value, String label, String descriptor,
        boolean professionallyValidated) {}
    public record RubricSummary(UUID rubricId, UUID ownerProcessId, String instrumentCode, String name,
        String status, long version, int versionCount, int latestVersion, boolean hasPublishedVersion) {}
    public record RubricDetail(RubricSummary rubric, List<VersionSummary> versions) {}
    public record VersionSummary(UUID versionId, int version, String status, String name,
        String instrumentCode, BigDecimal maximumScore, Instant publishedAt, int criteriaCount) {}
    public record RubricVersionView(UUID versionId, UUID rubricId, String name, String instrumentCode,
        int version, String status, BigDecimal maximumScore, Instant publishedAt,
        long rubricRevision, List<CriterionView> criteria) {}
    public record CriterionView(UUID criterionId, String code, String name, String descriptor,
        int position, boolean required, List<OptionView> options) {}
    public record OptionView(UUID optionId, BigDecimal value, String label, String descriptor,
        boolean professionallyValidated, int position) {}
    public record AssignmentView(UUID assignmentId, UUID processId, String instrumentCode,
        UUID rubricId, UUID versionId, String rubricName, int rubricVersion,
        BigDecimal maximumScore, long version) {}
}
