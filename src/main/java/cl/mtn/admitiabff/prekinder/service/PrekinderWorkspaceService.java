package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.config.PrekinderPaymentProperties;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderWorkspaceService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final EnvelopeEncryptionService encryption;
    private final ObjectMapper mapper;
    private final PrekinderPaymentProperties paymentDefaults;

    public PrekinderWorkspaceService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
        PrekinderAccessService access, EnvelopeEncryptionService encryption, ObjectMapper mapper,
        PrekinderPaymentProperties paymentDefaults) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.access = access;
        this.encryption = encryption; this.mapper = mapper; this.paymentDefaults = paymentDefaults;
    }

    public ProcessView createProcess(int academicYear, String name) {
        PrekinderActor actor = access.requireSensitiveAccess();
        UUID id = UUID.randomUUID();
        return transactions.execute(status -> {
            try {
                jdbc.update("""
                    INSERT INTO admission_processes(process_id, academic_year, name, status)
                    VALUES (:id, :year, :name, 'DRAFT')
                    """, Map.of("id", id, "year", academicYear, "name", name));
            } catch (DuplicateKeyException exception) {
                throw new IllegalStateException("Ya existe un proceso Prekínder con ese año y nombre", exception);
            }
            seedWaves(id);
            seedWorkflowStages(id);
            seedProcessConfiguration(id);
            seedCommunicationTemplates(id);
            audit(actor.id(), "PROCESS_CREATED", id);
            return process(id);
        });
    }

    public List<ProcessView> listProcesses() {
        access.requireSensitiveAccess();
        return jdbc.query("""
            SELECT p.process_id, p.academic_year, p.name, p.status, p.starts_at, p.ends_at, p.version,
                   count(a.application_id) AS application_count,
                   (p.status = 'PUBLISHED'
                     AND (p.starts_at IS NULL OR p.starts_at <= now())
                     AND (p.ends_at IS NULL OR p.ends_at >= now())) AS accepting_applications
              FROM admission_processes p
              LEFT JOIN applications a ON a.process_id = p.process_id
             GROUP BY p.process_id
             ORDER BY p.academic_year DESC, p.created_at DESC
            """, Map.of(), (rs, row) -> new ProcessView(
                rs.getObject("process_id", UUID.class), rs.getInt("academic_year"), rs.getString("name"),
                rs.getString("status"), instant(rs.getTimestamp("starts_at")), instant(rs.getTimestamp("ends_at")),
                rs.getLong("version"), rs.getLong("application_count"), rs.getBoolean("accepting_applications")));
    }

    /**
     * Exposes only the minimum scheduling data a guardian needs to choose the
     * Prekinder flow. Administrative counters and unpublished processes stay
     * behind {@link #listProcesses()}.
     */
    public List<ApplicationOption> applicationOptions() {
        access.requireActor();
        return jdbc.query("""
            WITH active_waves AS (
                SELECT w.*,
                       count(*) OVER (PARTITION BY w.process_id) AS active_count
                  FROM process_waves w
                 WHERE w.status = 'PUBLISHED'
                   AND w.opens_at <= now()
                   AND w.closes_at >= now()
            )
            SELECT p.process_id, p.academic_year, p.name,
                   w.wave_id, w.wave_type, w.opens_at, w.closes_at
              FROM admission_processes p
              JOIN active_waves w ON w.process_id = p.process_id AND w.active_count = 1
             WHERE p.status = 'PUBLISHED'
               AND (p.starts_at IS NULL OR p.starts_at <= now())
               AND (p.ends_at IS NULL OR p.ends_at >= now())
             ORDER BY p.academic_year DESC, p.created_at DESC, w.position
            """, Map.of(), (rs, row) -> new ApplicationOption(
                rs.getObject("process_id", UUID.class), rs.getInt("academic_year"), rs.getString("name"),
                rs.getObject("wave_id", UUID.class), rs.getString("wave_type"),
                instant(rs.getTimestamp("opens_at")), instant(rs.getTimestamp("closes_at"))));
    }

    public ProcessView publishProcess(UUID processId, Instant startsAt, Instant endsAt) {
        PrekinderActor actor = access.requireSensitiveAccess();
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("La fecha de cierre debe ser posterior a la fecha de apertura");
        }
        return transactions.execute(status -> {
            List<String> blockers = openingBlockers(processId);
            if (!blockers.isEmpty()) {
                throw PrekinderDomainException.conflict("PROCESS_NOT_READY",
                    "Completa la preparación del proceso antes de abrirlo: " + String.join(", ", blockers));
            }
            int updated = jdbc.update("""
                UPDATE admission_processes
                   SET status = 'PUBLISHED', starts_at = :startsAt, ends_at = :endsAt,
                       version = version + 1, updated_at = now()
                 WHERE process_id = :id AND status = 'DRAFT'
                """, new MapSqlParameterSource().addValue("id", processId)
                    .addValue("startsAt", java.sql.Timestamp.from(startsAt))
                    .addValue("endsAt", java.sql.Timestamp.from(endsAt)));
            if (updated != 1) {
                throw new IllegalStateException("El proceso no existe o ya fue habilitado");
            }
            audit(actor.id(), "PROCESS_PUBLISHED", processId);
            return process(processId);
        });
    }

    public ApplicationView createApplication(UUID processId, Identity identity) {
        PrekinderActor actor = access.requireSensitiveAccess();
        return transactions.execute(status -> {
            requireOpenProcess(processId);
            Identity normalizedIdentity = normalize(identity);
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(:key)", Map.of("key", lockKey(normalizedIdentity.rut())),
                (rs, row) -> Boolean.TRUE);
            if (rutAlreadyRegistered(processId, normalizedIdentity.rut())) {
                throw new IllegalStateException("Ya existe una postulación para este RUT en el proceso seleccionado");
            }
            UUID familyId = UUID.randomUUID();
            UUID applicantId = UUID.randomUUID();
            UUID applicationId = UUID.randomUUID();
            var encrypted = encryption.encrypt(json(normalizedIdentity),
                "prekinder|applicants|" + applicantId + "|application:" + applicationId + "|identity");
            jdbc.update("INSERT INTO families(family_id) VALUES (:id)", Map.of("id", familyId));
            var values = new MapSqlParameterSource()
                .addValue("applicantId", applicantId).addValue("familyId", familyId)
                .addValue("ciphertext", encrypted.ciphertext()).addValue("iv", encrypted.iv())
                .addValue("wrappedDek", encrypted.wrappedDek()).addValue("wrappedDekIv", encrypted.wrappedDekIv())
                .addValue("keyVersion", encrypted.keyVersion());
            jdbc.update("""
                INSERT INTO applicants(applicant_id, family_id, identity_ciphertext, identity_iv,
                  identity_wrapped_dek, identity_wrapped_dek_iv, identity_key_version)
                VALUES (:applicantId, :familyId, :ciphertext, :iv, :wrappedDek, :wrappedDekIv, :keyVersion)
                """, values);
            jdbc.update("""
                INSERT INTO applications(application_id, applicant_id, process_id, status,
                    payment_required, payment_status)
                VALUES (:id, :applicantId, :processId, 'DRAFT',
                    (SELECT payment_enabled FROM prekinder_process_configuration WHERE process_id = :processId),
                    CASE WHEN (SELECT payment_enabled FROM prekinder_process_configuration WHERE process_id = :processId)
                         THEN 'PENDING' ELSE 'NOT_REQUIRED' END)
                """, Map.of("id", applicationId, "applicantId", applicantId, "processId", processId));
            audit(actor.id(), "APPLICATION_CREATED", applicationId);
            return new ApplicationView(applicationId, applicantId, processId, "DRAFT", normalizedIdentity, Instant.now());
        });
    }

    public List<ApplicationView> listApplications(UUID processId) {
        access.requireSensitiveAccess();
        process(processId);
        return jdbc.query("""
            SELECT a.application_id, a.applicant_id, a.process_id, a.status, a.created_at,
                   ap.identity_ciphertext, ap.identity_iv, ap.identity_wrapped_dek,
                   ap.identity_wrapped_dek_iv, ap.identity_key_version
              FROM applications a
              JOIN applicants ap ON ap.applicant_id = a.applicant_id
             WHERE a.process_id = :processId
             ORDER BY a.created_at DESC
             LIMIT 1000
            """, Map.of("processId", processId), (rs, row) -> {
                UUID applicationId = rs.getObject("application_id", UUID.class);
                UUID applicantId = rs.getObject("applicant_id", UUID.class);
                return new ApplicationView(applicationId, applicantId, processId, rs.getString("status"),
                    decryptIdentity(applicationId, applicantId, rs.getString("identity_ciphertext"),
                        rs.getString("identity_iv"), rs.getString("identity_wrapped_dek"),
                        rs.getString("identity_wrapped_dek_iv"), rs.getString("identity_key_version")),
                    rs.getTimestamp("created_at").toInstant());
            });
    }

    public EvaluationView createEvaluation(UUID applicationId, String typeCode) {
        PrekinderActor actor = access.requireActor();
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO evaluations(evaluation_id, application_id, evaluator_id, type_code, status)
            VALUES (:id, :applicationId, :actorId, :typeCode, 'IN_PROGRESS')
            """, Map.of("id", id, "applicationId", applicationId, "actorId", actor.id(), "typeCode", typeCode));
        audit(actor.id(), "EVALUATION_CREATED", id);
        return new EvaluationView(id, applicationId, typeCode, "IN_PROGRESS", 0, 0);
    }

    public List<EvaluationView> listEvaluations() {
        access.requireActor();
        return jdbc.query("""
            SELECT evaluation_id, application_id, type_code, status, server_sequence, version
              FROM evaluations ORDER BY updated_at DESC LIMIT 500
            """, Map.of(), (rs, row) -> new EvaluationView(rs.getObject("evaluation_id", UUID.class),
                rs.getObject("application_id", UUID.class), rs.getString("type_code"), rs.getString("status"),
                rs.getLong("server_sequence"), rs.getLong("version")));
    }

    private void audit(UUID actorId, String action, UUID aggregateId) {
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result)
            VALUES (:id, :actor, :action, 'PREKINDER', :aggregate, 'SUCCESS')
            """, Map.of("id", UUID.randomUUID(), "actor", actorId, "action", action, "aggregate", aggregateId));
    }

    private void seedWaves(UUID processId) {
        List<String> types = List.of("SIBLINGS", "STAFF_OR_ALUMNI", "NEW_FAMILIES");
        for (int index = 0; index < types.size(); index++) {
            jdbc.update("""
                INSERT INTO process_waves(wave_id, process_id, wave_type, position, status)
                VALUES (:id, :processId, :type, :position, 'DRAFT')
                """, Map.of("id", UUID.randomUUID(), "processId", processId,
                    "type", types.get(index), "position", index + 1));
        }
    }

    private void seedWorkflowStages(UUID processId) {
        List<String[]> stages = List.of(
            new String[]{"APPLICANT", "Postulante y familia"},
            new String[]{"QUESTIONNAIRE", "Cuestionario familiar"},
            new String[]{"INCLUSION", "Inclusión"},
            new String[]{"BACKGROUND", "Antecedentes generales"},
            new String[]{"REVIEW", "Revisión y envío"},
            new String[]{"COORDINATION", "Revisión de coordinación"},
            new String[]{"SCHEDULE", "Agenda"},
            new String[]{"EVALUATION", "Evaluaciones"},
            new String[]{"COMMITTEE", "Comisión"},
            new String[]{"RESULT", "Resultado y oferta"}
        );
        for (int index = 0; index < stages.size(); index++) {
            String[] stage = stages.get(index);
            jdbc.update("""
                INSERT INTO workflow_stages(stage_id, process_id, code, name, position)
                VALUES (:id, :processId, :code, :name, :position)
                """, Map.of("id", UUID.randomUUID(), "processId", processId,
                    "code", stage[0], "name", stage[1], "position", index));
        }
    }

    private void seedProcessConfiguration(UUID processId) {
        jdbc.update("""
            INSERT INTO prekinder_process_configuration(process_id, payment_enabled, payment_amount,
                payment_currency, payment_glosa, payment_due_days)
            VALUES (:processId, :enabled, :amount, :currency, :glosa, :dueDays)
            ON CONFLICT (process_id) DO NOTHING
            """, new MapSqlParameterSource().addValue("processId", processId)
            .addValue("enabled", paymentDefaults.enabled()).addValue("amount", paymentDefaults.applicationFee())
            .addValue("currency", paymentDefaults.currency()).addValue("glosa", paymentDefaults.paymentGlosa())
            .addValue("dueDays", Math.max(1, paymentDefaults.dueDays())));
        jdbc.update("""
            INSERT INTO prekinder_process_configuration_versions(
                configuration_version_id, process_id, version, snapshot)
            SELECT :id, process_id, version,
                   jsonb_build_object(
                     'paymentEnabled', payment_enabled, 'paymentAmount', payment_amount,
                     'paymentCurrency', payment_currency, 'paymentGlosa', payment_glosa,
                     'paymentDueDays', payment_due_days, 'inclusionEnabled', inclusion_enabled,
                     'inclusionDocumentsRequired', inclusion_documents_required,
                     'minimumAgeMonths', minimum_age_months, 'maximumAgeMonths', maximum_age_months,
                     'applicantWeight', applicant_weight, 'familyWeight', family_weight)
              FROM prekinder_process_configuration WHERE process_id = :processId
            ON CONFLICT (process_id, version) DO NOTHING
            """, Map.of("id", UUID.randomUUID(), "processId", processId));
        jdbc.update("""
            INSERT INTO scoring_policies(scoring_policy_id, process_id, version, status,
                applicant_weight, family_weight, formula_document, published_at)
            SELECT :id, process_id, 1, 'PUBLISHED', applicant_weight, family_weight,
                   jsonb_build_object('source', 'PROCESS_CONFIGURATION', 'configurationVersion', version), now()
              FROM prekinder_process_configuration WHERE process_id = :processId
            """, Map.of("id", UUID.randomUUID(), "processId", processId));
    }

    private void seedCommunicationTemplates(UUID processId) {
        List<String[]> templates = List.of(
            new String[]{"APPLICATION_SUBMITTED", "Postulación recibida", "Postulación Prekínder recibida",
                "<h1>Postulación recibida</h1><p>Recibimos la postulación de {{applicantName}}.</p>"},
            new String[]{"SCHEDULE_ASSIGNED", "Jornada agendada", "Jornada de evaluación Prekínder",
                "<h1>Jornada agendada</h1><p>Revisa en el portal la fecha asignada a {{applicantName}}.</p>"},
            new String[]{"RESULT_ACCEPTED", "Resultado aceptado", "Resultado proceso de admisión Prekínder",
                "<h1>Resultado disponible</h1><p>El resultado de {{applicantName}} ya está en el portal.</p>"},
            new String[]{"RESULT_WAITLIST", "Resultado lista de espera", "Resultado proceso de admisión Prekínder",
                "<h1>Resultado disponible</h1><p>El resultado de {{applicantName}} ya está en el portal.</p>"},
            new String[]{"RESULT_REJECTED", "Resultado no admitido", "Resultado proceso de admisión Prekínder",
                "<h1>Resultado disponible</h1><p>El resultado de {{applicantName}} ya está en el portal.</p>"},
            new String[]{"RESULT_RECTIFICATION", "Rectificación de resultado", "Rectificación de resultado de admisión Prekínder",
                "<h1>Resultado rectificado</h1><p>Existe una actualización para {{applicantName}} en el portal.</p>"}
        );
        for (String[] template : templates) {
            UUID templateId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO prekinder_communication_templates(
                    communication_template_id, process_id, event_code, name)
                VALUES (:id, :processId, :eventCode, :name)
                """, Map.of("id", templateId, "processId", processId,
                    "eventCode", template[0], "name", template[1]));
            jdbc.update("""
                INSERT INTO prekinder_communication_template_versions(
                    communication_template_version_id, communication_template_id, version,
                    status, subject, body_html, allowed_variables, published_at)
                VALUES (:id, :templateId, 1, 'PUBLISHED', :subject, :body,
                    CAST(:variables AS jsonb), now())
                """, Map.of("id", UUID.randomUUID(), "templateId", templateId,
                    "subject", template[2], "body", template[3],
                    "variables", "[\"applicantName\",\"processName\",\"portalUrl\"]"));
        }
    }

    private List<String> openingBlockers(UUID processId) {
        List<String> blockers = new java.util.ArrayList<>();
        Long configuration = jdbc.queryForObject(
            "SELECT count(*) FROM prekinder_process_configuration WHERE process_id = :id",
            Map.of("id", processId), Long.class);
        if (configuration == null || configuration == 0) blockers.add("configuración general");
        Long stages = jdbc.queryForObject("""
            SELECT count(*) FROM process_waves
             WHERE process_id = :id AND opens_at IS NOT NULL AND closes_at IS NOT NULL
            """, Map.of("id", processId), Long.class);
        if (stages == null || stages < 3) blockers.add("calendario de las tres etapas");
        Long communication = jdbc.queryForObject("""
            SELECT count(*) FROM prekinder_communication_templates template
              JOIN prekinder_communication_template_versions version
                ON version.communication_template_id = template.communication_template_id
             WHERE template.process_id = :id AND template.event_code = 'APPLICATION_SUBMITTED'
               AND version.status = 'PUBLISHED'
            """, Map.of("id", processId), Long.class);
        if (communication == null || communication == 0) blockers.add("comunicación de postulación recibida");
        return blockers;
    }

    private ProcessView process(UUID processId) {
        List<ProcessView> result = jdbc.query("""
            SELECT p.process_id, p.academic_year, p.name, p.status, p.starts_at, p.ends_at, p.version,
                   count(a.application_id) AS application_count,
                   (p.status = 'PUBLISHED'
                     AND (p.starts_at IS NULL OR p.starts_at <= now())
                     AND (p.ends_at IS NULL OR p.ends_at >= now())) AS accepting_applications
              FROM admission_processes p
              LEFT JOIN applications a ON a.process_id = p.process_id
             WHERE p.process_id = :id
             GROUP BY p.process_id
            """, Map.of("id", processId), (rs, row) -> new ProcessView(
                rs.getObject("process_id", UUID.class), rs.getInt("academic_year"), rs.getString("name"),
                rs.getString("status"), instant(rs.getTimestamp("starts_at")), instant(rs.getTimestamp("ends_at")),
                rs.getLong("version"), rs.getLong("application_count"), rs.getBoolean("accepting_applications")));
        if (result.isEmpty()) throw new IllegalArgumentException("Proceso Prekínder no encontrado");
        return result.getFirst();
    }

    private void requireOpenProcess(UUID processId) {
        ProcessView process = process(processId);
        if (!process.acceptingApplications()) {
            throw new IllegalStateException("El proceso debe estar publicado y dentro de su periodo de postulación");
        }
    }

    private boolean rutAlreadyRegistered(UUID processId, String rut) {
        return jdbc.query("""
            SELECT a.application_id, a.applicant_id, ap.identity_ciphertext, ap.identity_iv,
                   ap.identity_wrapped_dek, ap.identity_wrapped_dek_iv, ap.identity_key_version
              FROM applications a
              JOIN applicants ap ON ap.applicant_id = a.applicant_id
             WHERE a.process_id = :processId
            """, Map.of("processId", processId), (rs, row) -> decryptIdentity(
                rs.getObject("application_id", UUID.class), rs.getObject("applicant_id", UUID.class),
                rs.getString("identity_ciphertext"), rs.getString("identity_iv"), rs.getString("identity_wrapped_dek"),
                rs.getString("identity_wrapped_dek_iv"), rs.getString("identity_key_version")))
            .stream().anyMatch(existing -> existing.rut().equals(rut));
    }

    private Identity decryptIdentity(UUID applicationId, UUID applicantId, String ciphertext, String iv,
                                     String wrappedDek, String wrappedDekIv, String keyVersion) {
        String aad = "prekinder|applicants|" + applicantId + "|application:" + applicationId + "|identity";
        String plaintext = encryption.decrypt(new EncryptedPayload(
            ciphertext, iv, wrappedDek, wrappedDekIv, keyVersion), aad);
        try {
            return mapper.readValue(plaintext, Identity.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("La identidad cifrada no tiene un formato válido", exception);
        }
    }

    private static Identity normalize(Identity identity) {
        if (identity == null) throw new IllegalArgumentException("La identidad del postulante es obligatoria");
        return new Identity(PrekinderRut.normalize(identity.rut()), identity.firstName().trim(), identity.paternalLastName().trim(),
            identity.maternalLastName() == null ? "" : identity.maternalLastName().trim());
    }

    private static long lockKey(String rut) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                ("prekinder-application|" + rut).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible", exception);
        }
    }

    private static Instant instant(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Identidad inválida"); }
    }

    public record Identity(String rut, String firstName, String paternalLastName, String maternalLastName) {}
    public record ProcessView(UUID processId, int academicYear, String name, String status, Instant startsAt,
                              Instant endsAt, long version, long applicationCount, boolean acceptingApplications) {}
    public record ApplicationOption(UUID processId, int academicYear, String name, UUID waveId, String waveType,
                                    Instant opensAt, Instant closesAt) {}
    public record ApplicationView(UUID applicationId, UUID applicantId, UUID processId, String status,
                                  Identity identity, Instant createdAt) {}
    public record EvaluationView(UUID evaluationId, UUID applicationId, String typeCode, String status,
                                 long serverSequence, long version) {}
}
