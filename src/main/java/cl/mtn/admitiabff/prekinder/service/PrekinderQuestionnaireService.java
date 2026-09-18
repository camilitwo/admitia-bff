package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
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
public class PrekinderQuestionnaireService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final ObjectMapper mapper;

    public PrekinderQuestionnaireService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
                                         @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
                                         PrekinderAccessService access, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.mapper = mapper;
    }

    public QuestionnaireView get(UUID processId) {
        access.requireAdmin();
        return load(processId);
    }

    public QuestionnaireView save(UUID processId, UUID versionId, Map<String, Object> schema, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        validateSchema(schema);
        int updated = jdbc.update("""
            UPDATE form_template_versions version
               SET schema_document = CAST(:schema AS jsonb), row_version = row_version + 1
              FROM form_templates template
             WHERE version.template_id = template.template_id
               AND template.process_id = :processId AND template.code = 'COMPLEMENTARY_FORM'
               AND version.template_version_id = :versionId AND version.status = 'DRAFT'
               AND version.row_version = :expectedVersion
            """, new MapSqlParameterSource().addValue("schema", json(schema))
            .addValue("processId", processId).addValue("versionId", versionId)
            .addValue("expectedVersion", expectedVersion));
        if (updated != 1) throw new VersionConflictException("El cuestionario cambió o ya fue publicado");
        audit(actor.id(), "QUESTIONNAIRE_DRAFT_UPDATED", processId, Map.of("versionId", versionId));
        return load(processId);
    }

    public QuestionnaireView publish(UUID processId, UUID versionId, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            VersionView target = version(processId, versionId);
            if (!"DRAFT".equals(target.status()) || target.rowVersion() != expectedVersion) {
                throw new VersionConflictException("El cuestionario cambió o ya fue publicado");
            }
            validateSchema(target.schema());
            jdbc.update("""
                UPDATE form_template_versions SET status = 'SUPERSEDED'
                 WHERE template_id = :templateId AND status = 'PUBLISHED'
                """, Map.of("templateId", target.templateId()));
            int updated = jdbc.update("""
                UPDATE form_template_versions
                   SET status = 'PUBLISHED', published_at = now(), row_version = row_version + 1
                 WHERE template_version_id = :versionId AND status = 'DRAFT' AND row_version = :expectedVersion
                """, Map.of("versionId", versionId, "expectedVersion", expectedVersion));
            if (updated != 1) throw new VersionConflictException("El cuestionario cambió durante la publicación");
            audit(actor.id(), "QUESTIONNAIRE_PUBLISHED", processId, Map.of("versionId", versionId));
            return load(processId);
        });
    }

    public QuestionnaireView duplicate(UUID processId) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            UUID templateId = templateId(processId);
            Long drafts = jdbc.queryForObject("""
                SELECT count(*) FROM form_template_versions WHERE template_id = :id AND status = 'DRAFT'
                """, Map.of("id", templateId), Long.class);
            if (drafts != null && drafts > 0) throw PrekinderDomainException.conflict(
                "QUESTIONNAIRE_DRAFT_EXISTS", "Ya existe una versión borrador del cuestionario");
            Map<String, Object> source = jdbc.queryForObject("""
                SELECT schema_document::text AS schema FROM form_template_versions
                 WHERE template_id = :id ORDER BY (status = 'PUBLISHED') DESC, version DESC LIMIT 1
                """, Map.of("id", templateId), (rs, row) -> parse(rs.getString("schema")));
            Integer next = jdbc.queryForObject("""
                SELECT coalesce(max(version), 0) + 1 FROM form_template_versions WHERE template_id = :id
                """, Map.of("id", templateId), Integer.class);
            UUID versionId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO form_template_versions(template_version_id, template_id, version, status,
                    schema_document, created_by)
                VALUES (:id, :templateId, :version, 'DRAFT', CAST(:schema AS jsonb), :actorId)
                """, new MapSqlParameterSource().addValue("id", versionId).addValue("templateId", templateId)
                .addValue("version", next).addValue("schema", json(source)).addValue("actorId", actor.id()));
            audit(actor.id(), "QUESTIONNAIRE_VERSION_CREATED", processId, Map.of("versionId", versionId));
            return load(processId);
        });
    }

    private QuestionnaireView load(UUID processId) {
        UUID templateId = templateId(processId);
        String name = jdbc.queryForObject("SELECT name FROM form_templates WHERE template_id = :id",
            Map.of("id", templateId), String.class);
        List<VersionView> versions = jdbc.query("""
            SELECT template_version_id, template_id, version, status, schema_document::text AS schema,
                   row_version, created_at, published_at
              FROM form_template_versions WHERE template_id = :id ORDER BY version DESC
            """, Map.of("id", templateId), (rs, row) -> new VersionView(
                rs.getObject("template_version_id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getInt("version"), rs.getString("status"), parse(rs.getString("schema")),
                rs.getLong("row_version"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("published_at"))));
        return new QuestionnaireView(templateId, processId, name, versions);
    }

    private VersionView version(UUID processId, UUID versionId) {
        return jdbc.queryForObject("""
            SELECT version.template_version_id, version.template_id, version.version, version.status,
                   version.schema_document::text AS schema, version.row_version,
                   version.created_at, version.published_at
              FROM form_template_versions version JOIN form_templates template ON template.template_id = version.template_id
             WHERE template.process_id = :processId AND template.code = 'COMPLEMENTARY_FORM'
               AND version.template_version_id = :versionId
            """, Map.of("processId", processId, "versionId", versionId), (rs, row) -> new VersionView(
                rs.getObject("template_version_id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getInt("version"), rs.getString("status"), parse(rs.getString("schema")),
                rs.getLong("row_version"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("published_at"))));
    }

    private UUID templateId(UUID processId) {
        List<UUID> ids = jdbc.queryForList("""
            SELECT template_id FROM form_templates
             WHERE process_id = :id AND code = 'COMPLEMENTARY_FORM'
            """, Map.of("id", processId), UUID.class);
        if (ids.isEmpty()) throw new IllegalArgumentException("Cuestionario Prekínder no encontrado");
        return ids.getFirst();
    }

    private void validateSchema(Map<String, Object> schema) {
        if (schema == null || !schema.containsKey("schemaVersion") || !schema.containsKey("renderer")) {
            throw new IllegalArgumentException("El cuestionario requiere schemaVersion y renderer");
        }
        Object sections = schema.get("sections");
        if (!(sections instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("El cuestionario requiere al menos una sección");
        }
    }

    private Map<String, Object> parse(String value) {
        try { return mapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("Esquema de cuestionario no válido", exception); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Esquema de cuestionario no válido", exception); }
    }

    private void audit(UUID actorId, String action, UUID processId, Map<String, ?> metadata) {
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result, metadata)
            VALUES (:id, :actorId, :action, 'QUESTIONNAIRE', :processId, 'SUCCESS', CAST(:metadata AS jsonb))
            """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("actorId", actorId)
            .addValue("action", action).addValue("processId", processId).addValue("metadata", json(metadata)));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record QuestionnaireView(UUID templateId, UUID processId, String name, List<VersionView> versions) {}
    public record VersionView(UUID versionId, UUID templateId, int version, String status,
                              Map<String, Object> schema, long rowVersion, Instant createdAt, Instant publishedAt) {}
}
