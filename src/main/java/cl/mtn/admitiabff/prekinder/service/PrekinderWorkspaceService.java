package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class PrekinderWorkspaceService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final EnvelopeEncryptionService encryption;
    private final ObjectMapper mapper;

    public PrekinderWorkspaceService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
        PrekinderAccessService access, EnvelopeEncryptionService encryption, ObjectMapper mapper) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.access = access;
        this.encryption = encryption; this.mapper = mapper;
    }

    public ProcessView createProcess(int academicYear, String name) {
        access.requireSensitiveAccess();
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO admission_processes(process_id, academic_year, name, status)
            VALUES (:id, :year, :name, 'DRAFT')
            """, Map.of("id", id, "year", academicYear, "name", name));
        return new ProcessView(id, academicYear, name, "DRAFT");
    }

    public ApplicationView createApplication(UUID processId, Identity identity) {
        PrekinderActor actor = access.requireSensitiveAccess();
        return transactions.execute(status -> {
            UUID familyId = UUID.randomUUID();
            UUID applicantId = UUID.randomUUID();
            UUID applicationId = UUID.randomUUID();
            var encrypted = encryption.encrypt(json(identity),
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
                INSERT INTO applications(application_id, applicant_id, process_id, status)
                VALUES (:id, :applicantId, :processId, 'DRAFT')
                """, Map.of("id", applicationId, "applicantId", applicantId, "processId", processId));
            audit(actor.id(), "APPLICATION_CREATED", applicationId);
            return new ApplicationView(applicationId, applicantId, processId, "DRAFT", identity);
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

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Identidad inválida"); }
    }

    public record Identity(String firstName, String paternalLastName, String maternalLastName) {}
    public record ProcessView(UUID processId, int academicYear, String name, String status) {}
    public record ApplicationView(UUID applicationId, UUID applicantId, UUID processId, String status, Identity identity) {}
    public record EvaluationView(UUID evaluationId, UUID applicationId, String typeCode, String status,
                                 long serverSequence, long version) {}
}
