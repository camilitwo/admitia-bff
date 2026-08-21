package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderCommunicationTemplateService {
    private static final Set<String> ALLOWED_VARIABLES = Set.of(
        "applicantName", "processName", "portalUrl", "deadline"
    );
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}", Pattern.CASE_INSENSITIVE);
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final ObjectMapper mapper;

    public PrekinderCommunicationTemplateService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
        PrekinderAccessService access, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.mapper = mapper;
    }

    public List<TemplateView> templates(UUID processId) {
        access.requireAdmin();
        return jdbc.query("""
            SELECT template.communication_template_id, template.process_id, template.event_code,
                   template.name, template.status, template.version,
                   version.communication_template_version_id, version.version AS content_version,
                   version.status AS content_status, version.subject, version.body_html,
                   version.allowed_variables::text AS allowed_variables, version.published_at
              FROM prekinder_communication_templates template
              JOIN prekinder_communication_template_versions version
                ON version.communication_template_id = template.communication_template_id
             WHERE template.process_id = :id
             ORDER BY template.event_code, version.version DESC
            """, Map.of("id", processId), (rs, row) -> new TemplateView(
                rs.getObject("communication_template_id", UUID.class), rs.getObject("process_id", UUID.class),
                rs.getString("event_code"), rs.getString("name"), rs.getString("status"),
                rs.getLong("version"), rs.getObject("communication_template_version_id", UUID.class),
                rs.getInt("content_version"), rs.getString("content_status"), rs.getString("subject"),
                rs.getString("body_html"), readVariables(rs.getString("allowed_variables")),
                instant(rs.getTimestamp("published_at"))));
    }

    public TemplateView duplicate(UUID templateId) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            UUID sourceId = jdbc.queryForObject("""
                SELECT communication_template_version_id
                  FROM prekinder_communication_template_versions
                 WHERE communication_template_id = :id
                 ORDER BY CASE status WHEN 'PUBLISHED' THEN 0 ELSE 1 END, version DESC LIMIT 1
                """, Map.of("id", templateId), UUID.class);
            Integer nextVersion = jdbc.queryForObject("""
                SELECT coalesce(max(version), 0) + 1 FROM prekinder_communication_template_versions
                 WHERE communication_template_id = :id
                """, Map.of("id", templateId), Integer.class);
            UUID targetId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO prekinder_communication_template_versions(
                    communication_template_version_id, communication_template_id, version,
                    status, subject, body_html, allowed_variables)
                SELECT :targetId, communication_template_id, :nextVersion, 'DRAFT',
                       subject, body_html, allowed_variables
                  FROM prekinder_communication_template_versions
                 WHERE communication_template_version_id = :sourceId
                """, Map.of("targetId", targetId, "nextVersion", nextVersion, "sourceId", sourceId));
            audit(actor.id(), "COMMUNICATION_VERSION_DUPLICATED", targetId);
            return version(targetId);
        });
    }

    public TemplateView save(UUID versionId, String subject, String bodyHtml) {
        PrekinderActor actor = access.requireAdmin();
        List<String> variables = validate(subject, bodyHtml);
        int updated = jdbc.update("""
            UPDATE prekinder_communication_template_versions
               SET subject = :subject, body_html = :body, allowed_variables = CAST(:variables AS jsonb)
             WHERE communication_template_version_id = :id AND status = 'DRAFT'
            """, Map.of("id", versionId, "subject", subject.trim(), "body", bodyHtml.trim(),
                "variables", json(variables)));
        if (updated != 1) throw PrekinderDomainException.conflict("COMMUNICATION_IMMUTABLE",
            "Sólo las versiones borrador se pueden editar");
        audit(actor.id(), "COMMUNICATION_DRAFT_SAVED", versionId);
        return version(versionId);
    }

    public TemplateView publish(UUID versionId) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            TemplateView draft = version(versionId);
            if (!"DRAFT".equals(draft.contentStatus())) throw PrekinderDomainException.conflict(
                "COMMUNICATION_IMMUTABLE", "La versión ya no es borrador");
            validate(draft.subject(), draft.bodyHtml());
            jdbc.update("""
                UPDATE prekinder_communication_template_versions SET status = 'SUPERSEDED'
                 WHERE communication_template_id = :templateId AND status = 'PUBLISHED'
                """, Map.of("templateId", draft.templateId()));
            jdbc.update("""
                UPDATE prekinder_communication_template_versions
                   SET status = 'PUBLISHED', published_at = now()
                 WHERE communication_template_version_id = :id AND status = 'DRAFT'
                """, Map.of("id", versionId));
            audit(actor.id(), "COMMUNICATION_VERSION_PUBLISHED", versionId);
            return version(versionId);
        });
    }

    public void deleteDraft(UUID versionId) {
        PrekinderActor actor = access.requireAdmin();
        int deleted = jdbc.update("""
            DELETE FROM prekinder_communication_template_versions
             WHERE communication_template_version_id = :id AND status = 'DRAFT'
            """, Map.of("id", versionId));
        if (deleted != 1) throw PrekinderDomainException.conflict("COMMUNICATION_IMMUTABLE",
            "Sólo se pueden eliminar versiones borrador");
        audit(actor.id(), "COMMUNICATION_DRAFT_DELETED", versionId);
    }

    public Preview preview(UUID versionId) {
        TemplateView template = version(versionId);
        String subject = render(template.subject(), "Postulante de ejemplo", "Admisión Prekínder", "https://admitia.cl", "31-12-2026");
        String body = render(template.bodyHtml(), "Postulante de ejemplo", "Admisión Prekínder", "https://admitia.cl", "31-12-2026");
        return new Preview(subject, body, template.allowedVariables());
    }

    public TemplateView archive(UUID templateId, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        int updated = jdbc.update("""
            UPDATE prekinder_communication_templates
               SET status = 'ARCHIVED', version = version + 1, updated_at = now()
             WHERE communication_template_id = :id AND version = :version AND status = 'ACTIVE'
            """, Map.of("id", templateId, "version", expectedVersion));
        if (updated != 1) throw new VersionConflictException("La plantilla cambió");
        audit(actor.id(), "COMMUNICATION_ARCHIVED", templateId);
        return templatesForId(templateId).getFirst();
    }

    private TemplateView version(UUID versionId) {
        access.requireAdmin();
        return jdbc.queryForObject("""
            SELECT template.communication_template_id, template.process_id, template.event_code,
                   template.name, template.status, template.version,
                   version.communication_template_version_id, version.version AS content_version,
                   version.status AS content_status, version.subject, version.body_html,
                   version.allowed_variables::text AS allowed_variables, version.published_at
              FROM prekinder_communication_templates template
              JOIN prekinder_communication_template_versions version
                ON version.communication_template_id = template.communication_template_id
             WHERE version.communication_template_version_id = :id
            """, Map.of("id", versionId), (rs, row) -> new TemplateView(
                rs.getObject("communication_template_id", UUID.class), rs.getObject("process_id", UUID.class),
                rs.getString("event_code"), rs.getString("name"), rs.getString("status"), rs.getLong("version"),
                rs.getObject("communication_template_version_id", UUID.class), rs.getInt("content_version"),
                rs.getString("content_status"), rs.getString("subject"), rs.getString("body_html"),
                readVariables(rs.getString("allowed_variables")), instant(rs.getTimestamp("published_at"))));
    }

    private List<TemplateView> templatesForId(UUID templateId) {
        UUID processId = jdbc.queryForObject("SELECT process_id FROM prekinder_communication_templates WHERE communication_template_id = :id",
            Map.of("id", templateId), UUID.class);
        return templates(processId).stream().filter(value -> value.templateId().equals(templateId)).toList();
    }

    private static List<String> validate(String subject, String bodyHtml) {
        if (subject == null || subject.isBlank() || subject.length() > 200) throw new IllegalArgumentException("El asunto es obligatorio");
        if (bodyHtml == null || bodyHtml.isBlank() || bodyHtml.length() > 20000) throw new IllegalArgumentException("El cuerpo es obligatorio");
        String unsafe = bodyHtml.toLowerCase();
        if (unsafe.contains("<script") || unsafe.contains("<iframe") || unsafe.contains("<object")
            || unsafe.contains("javascript:") || unsafe.contains("onerror=") || unsafe.contains("onload=")) {
            throw new IllegalArgumentException("El cuerpo contiene HTML no permitido");
        }
        java.util.LinkedHashSet<String> variables = new java.util.LinkedHashSet<>();
        for (String value : List.of(subject, bodyHtml)) {
            Matcher matcher = VARIABLE.matcher(value);
            while (matcher.find()) {
                String variable = matcher.group(1);
                if (!ALLOWED_VARIABLES.contains(variable)) throw new IllegalArgumentException("Variable no permitida: " + variable);
                variables.add(variable);
            }
        }
        return List.copyOf(variables);
    }

    static String render(String value, String applicantName, String processName, String portalUrl, String deadline) {
        return value.replace("{{applicantName}}", applicantName).replace("{{processName}}", processName)
            .replace("{{portalUrl}}", portalUrl).replace("{{deadline}}", deadline);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Variables inválidas", exception); }
    }

    private List<String> readVariables(String value) {
        try { return mapper.readValue(value, mapper.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception exception) { return List.of(); }
    }

    private void audit(UUID actorId, String action, UUID aggregateId) {
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result)
            VALUES (:id, :actorId, :action, 'COMMUNICATION_TEMPLATE', :aggregateId, 'SUCCESS')
            """, Map.of("id", UUID.randomUUID(), "actorId", actorId, "action", action, "aggregateId", aggregateId));
    }

    private static Instant instant(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }

    public record TemplateView(UUID templateId, UUID processId, String eventCode, String name,
        String status, long version, UUID contentVersionId, int contentVersion, String contentStatus,
        String subject, String bodyHtml, List<String> allowedVariables, Instant publishedAt) {}
    public record Preview(String subject, String bodyHtml, List<String> variables) {}
}
