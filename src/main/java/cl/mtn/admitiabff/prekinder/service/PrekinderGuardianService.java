package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Lectura aislada para mostrar postulaciones Prekínder en el portal del apoderado. */
@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderGuardianService {
    private final NamedParameterJdbcTemplate jdbc;
    private final PrekinderAccessService access;
    private final EnvelopeEncryptionService encryption;
    private final ObjectMapper mapper;

    public PrekinderGuardianService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
                                    PrekinderAccessService access,
                                    EnvelopeEncryptionService encryption,
                                    ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.access = access;
        this.encryption = encryption;
        this.mapper = mapper;
    }

    public List<GuardianApplicationView> myApplications() {
        PrekinderActor actor = access.requireActor();
        return jdbc.query("""
            SELECT a.application_id, a.applicant_id, a.status, a.eligibility_status, a.created_at,
                   p.name AS process_name, p.academic_year,
                   ap.identity_ciphertext, ap.identity_iv, ap.identity_wrapped_dek,
                   ap.identity_wrapped_dek_iv, ap.identity_key_version
              FROM families f
              JOIN applicants ap ON ap.family_id = f.family_id
              JOIN applications a ON a.applicant_id = ap.applicant_id
              JOIN admission_processes p ON p.process_id = a.process_id
             WHERE f.external_reference = :actorReference
             ORDER BY a.created_at DESC
            """, Map.of("actorReference", actor.id().toString()), (rs, row) -> {
                UUID applicationId = rs.getObject("application_id", UUID.class);
                UUID applicantId = rs.getObject("applicant_id", UUID.class);
                ApplicantIdentity identity = decryptIdentity(applicationId, applicantId, new EncryptedPayload(
                    rs.getString("identity_ciphertext"), rs.getString("identity_iv"),
                    rs.getString("identity_wrapped_dek"), rs.getString("identity_wrapped_dek_iv"),
                    rs.getString("identity_key_version")));
                return new GuardianApplicationView(applicationId, "PREKINDER", identity.firstName(),
                    identity.paternalLastName(), identity.maternalLastName(), identity.birthDate(),
                    "Prekínder", rs.getString("process_name"), rs.getInt("academic_year"),
                    rs.getString("status"), rs.getString("eligibility_status"),
                    rs.getTimestamp("created_at").toInstant());
            });
    }

    private ApplicantIdentity decryptIdentity(UUID applicationId, UUID applicantId, EncryptedPayload payload) {
        try {
            String aad = "prekinder|applicants|" + applicantId + "|application:" + applicationId + "|identity";
            return mapper.readValue(encryption.decrypt(payload, aad), ApplicantIdentity.class);
        } catch (Exception exception) {
            throw new IllegalStateException("La identidad cifrada de Prekínder no tiene un formato válido", exception);
        }
    }

    private record ApplicantIdentity(String rut, String firstName, String paternalLastName,
                                     String maternalLastName, LocalDate birthDate, String familyEmail,
                                     String fatherEmail, String motherEmail) {}

    public record GuardianApplicationView(UUID applicationId, String source, String firstName,
                                          String paternalLastName, String maternalLastName, LocalDate birthDate,
                                          String gradeApplied, String processName, int academicYear,
                                          String status, String eligibilityStatus, Instant submissionDate) {}
}
