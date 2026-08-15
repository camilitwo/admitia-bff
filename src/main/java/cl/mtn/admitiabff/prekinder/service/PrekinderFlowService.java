package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderFlowService {
    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    private static final List<ProfessionalRoleDefinition> PROFESSIONAL_ROLES = List.of(
        new ProfessionalRoleDefinition("PK_ADMIN", "Administrador/a del proceso", "ADMINISTRACION", null, 1),
        new ProfessionalRoleDefinition("PK_COORDINATOR", "Coordinador/a Prekínder", "ADMINISTRACION", null, 2),
        new ProfessionalRoleDefinition("PK_RECEPTION", "Recepción y asistencia", "OPERACION", null, 3),
        new ProfessionalRoleDefinition("PK_DATA_ENTRY", "Registro y digitación", "OPERACION", null, 4),
        new ProfessionalRoleDefinition("PK_EVALUATOR_ACADEMIC", "Evaluador/a académico", "EVALUACION", "ACADEMIC", 5),
        new ProfessionalRoleDefinition("PK_EVALUATOR_PSYCHOMOTOR", "Evaluador/a de psicomotricidad", "EVALUACION", "PSYCHOMOTOR", 6),
        new ProfessionalRoleDefinition("PK_EVALUATOR_PSYCHOLOGY", "Psicólogo/a evaluador/a", "EVALUACION", "PSYCHOLOGY", 7),
        new ProfessionalRoleDefinition("PK_EVALUATOR_ENTRY_INDICATORS", "Evaluador/a de indicadores de ingreso", "EVALUACION", "ENTRY_INDICATORS", 8),
        new ProfessionalRoleDefinition("PK_EVALUATOR_GROUP_OBSERVATION", "Observador/a grupal", "EVALUACION", "GROUP_OBSERVATION", 9),
        new ProfessionalRoleDefinition("PK_EVALUATOR_LEARNING_SUPPORT", "Profesional de Apoyo al Aprendizaje", "EVALUACION", "LEARNING_SUPPORT", 10),
        new ProfessionalRoleDefinition("PK_EVALUATOR_DAP", "Profesional DAP", "EVALUACION", "DAP", 11),
        new ProfessionalRoleDefinition("PK_REVIEWER", "Revisor/a de informes", "DECISION_CONTROL", null, 12),
        new ProfessionalRoleDefinition("PK_COMMITTEE", "Integrante de comisión", "DECISION_CONTROL", null, 13),
        new ProfessionalRoleDefinition("PK_FINAL_APPROVER", "Responsable de decisión final", "DECISION_CONTROL", null, 14),
        new ProfessionalRoleDefinition("PK_AUDITOR", "Auditor/a del proceso", "DECISION_CONTROL", null, 15)
    );
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final PrekinderProfessionalAccountService professionalAccounts;
    private final EnvelopeEncryptionService encryption;
    private final ObjectMapper mapper;

    public PrekinderFlowService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
                                @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
                                PrekinderAccessService access,
                                PrekinderProfessionalAccountService professionalAccounts,
                                EnvelopeEncryptionService encryption,
                                ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.professionalAccounts = professionalAccounts;
        this.encryption = encryption;
        this.mapper = mapper;
    }

    public List<WaveView> waves(UUID processId) {
        access.requireActor();
        return jdbc.query("""
            SELECT wave_id, process_id, wave_type, position, status, opens_at, closes_at, version,
                   (status = 'PUBLISHED' AND opens_at <= now() AND closes_at >= now()) AS active
              FROM process_waves WHERE process_id = :processId ORDER BY position
            """, Map.of("processId", processId), (rs, row) -> new WaveView(
                rs.getObject("wave_id", UUID.class), rs.getObject("process_id", UUID.class),
                rs.getString("wave_type"), rs.getInt("position"), rs.getString("status"),
                instant(rs.getTimestamp("opens_at")), instant(rs.getTimestamp("closes_at")),
                rs.getLong("version"), rs.getBoolean("active")));
    }

    public WaveView configureWave(UUID waveId, Instant opensAt, Instant closesAt, String status, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        if (opensAt == null || closesAt == null || !closesAt.isAfter(opensAt)) {
            throw new IllegalArgumentException("La fecha de cierre debe ser posterior a la apertura");
        }
        if (!List.of("DRAFT", "PUBLISHED", "CLOSED", "CANCELLED").contains(status)) {
            throw new IllegalArgumentException("Estado de etapa inválido");
        }
        return transactions.execute(transaction -> {
            int updated;
            try {
                updated = jdbc.update("""
                    UPDATE process_waves SET opens_at = :opensAt, closes_at = :closesAt, status = :status,
                           version = version + 1, updated_at = now()
                     WHERE wave_id = :waveId AND version = :expectedVersion
                    """, new MapSqlParameterSource().addValue("waveId", waveId)
                        .addValue("opensAt", Timestamp.from(opensAt)).addValue("closesAt", Timestamp.from(closesAt))
                        .addValue("status", status).addValue("expectedVersion", expectedVersion));
            } catch (DataIntegrityViolationException exception) {
                throw PrekinderDomainException.conflict("STAGE_OVERLAP",
                    "La etapa publicada se superpone con otra etapa del mismo proceso");
            }
            if (updated != 1) throw new VersionConflictException("La etapa cambió");
            audit(actor.id(), "WAVE_CONFIGURED", "WAVE", waveId, Map.of(
                "status", status, "opensAt", opensAt.toString(), "closesAt", closesAt.toString()));
            return wave(waveId);
        });
    }

    public ApplicationView submitApplication(SubmitApplication command) {
        PrekinderActor actor = access.requireActor();
        String rut = PrekinderRut.normalize(command.rut());
        PrekinderAgePolicy.validate(command.birthDate(), Instant.now());
        String category = category(command.eligibility());
        WaveView wave = activeWave(command.processId());
        if (!wave.waveType().equals(category)) {
            throw PrekinderDomainException.forbidden("WAVE_RESTRICTION",
                "La etapa vigente corresponde a " + waveLabel(wave.waveType()) + " y la declaración no cumple sus requisitos");
        }
        validateApplicationDetails(command, category);
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(:key)", Map.of("key", identityLock(rut)),
                (rs, row) -> Boolean.TRUE);
            UUID familyId = UUID.randomUUID();
            UUID applicantId = UUID.randomUUID();
            UUID applicationId = UUID.randomUUID();
            if (blank(command.familyEmail()) && blank(command.fatherEmail()) && blank(command.motherEmail())) {
                throw new IllegalArgumentException("Registra al menos un correo de apoderado");
            }
            ApplicantIdentity identity = new ApplicantIdentity(rut, clean(command.firstName()), clean(command.paternalLastName()),
                cleanNullable(command.maternalLastName()), command.birthDate(), cleanNullable(command.familyEmail()),
                cleanNullable(command.fatherEmail()), cleanNullable(command.motherEmail()));
            EncryptedPayload encryptedIdentity = encryption.encrypt(json(identity),
                "prekinder|applicants|" + applicantId + "|application:" + applicationId + "|identity");
            jdbc.update("INSERT INTO families(family_id, external_reference) VALUES (:id, :external)",
                Map.of("id", familyId, "external", actor.id().toString()));
            jdbc.update("""
                INSERT INTO applicants(applicant_id, family_id, identity_ciphertext, identity_iv,
                    identity_wrapped_dek, identity_wrapped_dek_iv, identity_key_version)
                VALUES (:id, :familyId, :ciphertext, :iv, :wrappedDek, :wrappedDekIv, :keyVersion)
                """, encryptedValues(encryptedIdentity).addValue("id", applicantId).addValue("familyId", familyId));
            try {
                jdbc.update("""
                    INSERT INTO applications(application_id, applicant_id, process_id, wave_id, status,
                        eligibility_category, eligibility_status, applicant_identity_hash, submitted_at, submitted_by)
                    VALUES (:id, :applicantId, :processId, :waveId, 'SUBMITTED', :category, 'PENDING',
                        :identityHash, now(), :actorId)
                    """, new MapSqlParameterSource().addValue("id", applicationId).addValue("applicantId", applicantId)
                    .addValue("processId", command.processId()).addValue("waveId", wave.waveId())
                    .addValue("category", category).addValue("identityHash", sha256(rut))
                    .addValue("actorId", actor.id()));
            } catch (DataIntegrityViolationException exception) {
                throw PrekinderDomainException.conflict("DUPLICATE_APPLICATION",
                    "Ya existe una postulación activa para este postulante y proceso");
            }
            UUID declarationId = UUID.randomUUID();
            EncryptedPayload encryptedDeclaration = encryption.encrypt(json(command.eligibility()),
                "prekinder|eligibility|" + declarationId + "|application:" + applicationId);
            jdbc.update("""
                INSERT INTO eligibility_declarations(declaration_id, application_id, wave_id, category,
                    ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version)
                VALUES (:id, :applicationId, :waveId, :category, :ciphertext, :iv, :wrappedDek, :wrappedDekIv, :keyVersion)
                """, encryptedValues(encryptedDeclaration).addValue("id", declarationId)
                .addValue("applicationId", applicationId).addValue("waveId", wave.waveId())
                .addValue("category", category));
            EncryptedPayload encryptedForm = encryption.encrypt(json(command.applicationDetails()),
                "prekinder|application-form|application:" + applicationId + "|field:APPLICATION_FORM");
            jdbc.update("""
                INSERT INTO encrypted_field_values(field_value_id, aggregate_type, aggregate_id, field_code,
                    ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version, updated_by)
                VALUES (:id, 'APPLICATION', :applicationId, 'APPLICATION_FORM', :ciphertext, :iv,
                    :wrappedDek, :wrappedDekIv, :keyVersion, :actorId)
                """, encryptedValues(encryptedForm).addValue("id", UUID.randomUUID())
                .addValue("applicationId", applicationId).addValue("actorId", actor.id()));
            audit(actor.id(), "APPLICATION_SUBMITTED", "APPLICATION", applicationId,
                Map.of("waveType", category));
            return application(applicationId);
        });
    }

    public List<ApplicationView> applications(UUID processId) {
        access.requireAdmin();
        return jdbc.query("""
            SELECT a.application_id FROM applications a WHERE a.process_id = :processId
             ORDER BY a.created_at DESC LIMIT 2000
            """, Map.of("processId", processId), (rs, row) -> application(rs.getObject(1, UUID.class)));
    }

    public ApplicationView reviewEligibility(UUID applicationId, String decision, String reason, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        if (!List.of("VERIFIED", "REJECTED").contains(decision)) throw new IllegalArgumentException("Decisión inválida");
        return transactions.execute(status -> {
            UUID declarationId = jdbc.queryForObject("""
                SELECT declaration_id FROM eligibility_declarations
                 WHERE application_id = :applicationId AND version = :version FOR UPDATE
                """, Map.of("applicationId", applicationId, "version", expectedVersion), UUID.class);
            if (declarationId == null) throw new VersionConflictException("La declaración cambió");
            MapSqlParameterSource values = new MapSqlParameterSource().addValue("applicationId", applicationId)
                .addValue("decision", decision).addValue("actorId", actor.id()).addValue("version", expectedVersion);
            if (reason != null && !reason.isBlank()) {
                EncryptedPayload encryptedReason = encryption.encrypt(reason.trim(),
                    "prekinder|eligibility-review|" + declarationId);
                encryptedValues(encryptedReason, values, "reason");
            }
            int updated = jdbc.update("""
                UPDATE eligibility_declarations SET status = :decision, reviewed_by = :actorId, reviewed_at = now(),
                    review_reason_ciphertext = :reasonCiphertext, review_reason_iv = :reasonIv,
                    review_reason_wrapped_dek = :reasonWrappedDek, review_reason_wrapped_dek_iv = :reasonWrappedDekIv,
                    review_reason_key_version = :reasonKeyVersion, version = version + 1, updated_at = now()
                 WHERE application_id = :applicationId AND version = :version
                """, ensureReasonValues(values));
            if (updated != 1) throw new VersionConflictException("La declaración cambió");
            jdbc.update("""
                UPDATE applications SET eligibility_status = :decision,
                    status = CASE WHEN :decision = 'REJECTED' THEN 'INVALIDATED' ELSE 'UNDER_REVIEW' END,
                    invalidated_at = CASE WHEN :decision = 'REJECTED' THEN now() ELSE NULL END,
                    invalidated_by = CASE WHEN :decision = 'REJECTED' THEN :actorId ELSE NULL END,
                    version = version + 1, updated_at = now()
                 WHERE application_id = :applicationId
                """, Map.of("applicationId", applicationId, "decision", decision, "actorId", actor.id()));
            audit(actor.id(), "ELIGIBILITY_" + decision, "APPLICATION", applicationId, Map.of());
            return application(applicationId);
        });
    }

    public List<ProfessionalView> professionals(UUID processId) {
        access.requireAdmin();
        if (processId != null) {
            return jdbc.query("""
                SELECT p.professional_id, p.display_name, p.email, p.specialty, p.role_code, p.active, p.version,
                       a.legacy_user_id, r.role_code as assignment_role
                  FROM professional_profiles p
                  JOIN actors a ON a.actor_id = p.professional_id
                  JOIN prekinder_actor_role_assignments r ON r.actor_id = p.professional_id
                                                         AND r.process_id = :processId AND r.active
                 WHERE p.active
                ORDER BY p.display_name
                """, Map.of("processId", processId), (rs, row) -> professionalView(rs.getObject("professional_id", UUID.class),
                    (Long) rs.getObject("legacy_user_id"), rs.getString("display_name"), rs.getString("email"),
                    rs.getString("specialty"), rs.getString("role_code"), rs.getBoolean("active"), rs.getLong("version")));
        }
        return jdbc.query("""
            SELECT p.professional_id, p.display_name, p.email, p.specialty, p.role_code, p.active, p.version,
                   a.legacy_user_id
              FROM professional_profiles p JOIN actors a ON a.actor_id = p.professional_id
             ORDER BY p.active DESC, p.display_name
            """, Map.of(), (rs, row) -> professionalView(rs.getObject("professional_id", UUID.class),
                (Long) rs.getObject("legacy_user_id"), rs.getString("display_name"), rs.getString("email"),
                rs.getString("specialty"), rs.getString("role_code"), rs.getBoolean("active"), rs.getLong("version")));
    }

    public List<ProfessionalRoleDefinition> professionalRoles() {
        access.requireAdmin();
        return PROFESSIONAL_ROLES;
    }

    public List<AvailabilityView> availability(UUID professionalId, Instant from, Instant to) {
        access.requireAdmin();
        return jdbc.query("""
            SELECT availability_id, professional_id, starts_at, ends_at, status, version
              FROM professional_availability
             WHERE professional_id = :professionalId AND starts_at < :to AND ends_at > :from
             ORDER BY starts_at
            """, new MapSqlParameterSource().addValue("professionalId", professionalId)
            .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)),
            (rs, row) -> new AvailabilityView(rs.getObject("availability_id", UUID.class),
                rs.getObject("professional_id", UUID.class), instant(rs.getTimestamp("starts_at")),
                instant(rs.getTimestamp("ends_at")), rs.getString("status"), rs.getLong("version")));
    }

    public AvailabilityView saveAvailability(UUID professionalId, Instant startsAt, Instant endsAt, String state) {
        PrekinderActor actor = access.requireAdmin();
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) throw new IllegalArgumentException("Disponibilidad inválida");
        String normalized = "UNAVAILABLE".equals(state) ? "UNAVAILABLE" : "AVAILABLE";
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO professional_availability(availability_id, professional_id, starts_at, ends_at, status)
            VALUES (:id, :professionalId, :startsAt, :endsAt, :status)
            """, new MapSqlParameterSource().addValue("id", id).addValue("professionalId", professionalId)
            .addValue("startsAt", Timestamp.from(startsAt)).addValue("endsAt", Timestamp.from(endsAt))
            .addValue("status", normalized));
        audit(actor.id(), "PROFESSIONAL_AVAILABILITY_SAVED", "PROFESSIONAL", professionalId, Map.of("status", normalized));
        return jdbc.queryForObject("""
            SELECT availability_id, professional_id, starts_at, ends_at, status, version
              FROM professional_availability WHERE availability_id = :id
            """, Map.of("id", id), (rs, row) -> new AvailabilityView(rs.getObject("availability_id", UUID.class),
                rs.getObject("professional_id", UUID.class), instant(rs.getTimestamp("starts_at")),
                instant(rs.getTimestamp("ends_at")), rs.getString("status"), rs.getLong("version")));
    }

    public List<ScheduleBlockView> schedule(UUID processId, LocalDate date) {
        access.requireAdmin();
        return jdbc.query("""
            SELECT d.day_id, d.day_date, d.name, d.status AS day_status, d.version AS day_version,
                   b.block_id, b.starts_at, b.ends_at, b.duration_minutes, b.version AS block_version
              FROM evaluation_days d LEFT JOIN evaluation_blocks b ON b.day_id = d.day_id
             WHERE d.process_id = :processId AND d.day_date = :date ORDER BY b.starts_at
            """, Map.of("processId", processId, "date", date), (rs, row) -> new ScheduleBlockView(
                rs.getObject("day_id", UUID.class), rs.getObject("day_date", LocalDate.class), rs.getString("name"),
                rs.getString("day_status"), rs.getLong("day_version"), rs.getObject("block_id", UUID.class),
                instant(rs.getTimestamp("starts_at")), instant(rs.getTimestamp("ends_at")),
                rs.getInt("duration_minutes"), rs.getLong("block_version")));
    }

    public ProfessionalView saveProfessional(ProfessionalCommand command) {
        PrekinderActor admin = access.requireAdmin();
        if (command.processId() == null) throw new IllegalArgumentException("Selecciona el proceso Prekínder");
        List<Long> existingAccountLinks = command.professionalId() == null ? List.of() : jdbc.query(
            "SELECT legacy_user_id FROM actors WHERE actor_id = :id",
            Map.of("id", command.professionalId()), (rs, row) -> (Long) rs.getObject("legacy_user_id"));
        boolean accountRequired = command.professionalId() == null
            || existingAccountLinks.isEmpty() || existingAccountLinks.getFirst() == null;
        if (!accountRequired) {
            professionalAccounts.requireMatchingEmail(existingAccountLinks.getFirst(), clean(command.email()));
        }
        if (accountRequired && (command.password() == null || command.password().length() < 6)) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 6 caracteres");
        }
        ProfessionalRoleDefinition definition = professionalRole(command.roleCode());
        String role = definition.roleCode();
        String emailHash = sha256(clean(command.email()).toLowerCase());
        List<UUID> actorMatches = command.professionalId() == null
            ? jdbc.queryForList("SELECT actor_id FROM actors WHERE email_hash = :emailHash ORDER BY created_at LIMIT 1",
                Map.of("emailHash", emailHash), UUID.class)
            : List.of();
        UUID actorId = command.professionalId() == null
            ? (actorMatches.isEmpty() ? UUID.randomUUID() : actorMatches.getFirst()) : command.professionalId();
        PrekinderProfessionalAccountService.ProvisionedAccount account = accountRequired
            ? professionalAccounts.provision(clean(command.displayName()), clean(command.email()), command.password())
            : null;
        try {
            return transactions.execute(status -> {
                if (command.professionalId() == null) {
                    Long legacyUserId = account.userId();
                    if (actorMatches.isEmpty()) {
                        jdbc.update("""
                            INSERT INTO actors(actor_id, legacy_user_id, role_code, display_name, email_hash)
                            VALUES (:id, :legacyId, :actorRole, :displayName, :emailHash)
                            """, new MapSqlParameterSource().addValue("id", actorId).addValue("legacyId", legacyUserId)
                            .addValue("actorRole", role)
                            .addValue("displayName", clean(command.displayName())).addValue("emailHash", emailHash));
                    } else {
                        jdbc.update("""
                            UPDATE actors SET legacy_user_id = COALESCE(legacy_user_id, :legacyId),
                                role_code = :role, display_name = :name, updated_at = now()
                             WHERE actor_id = :id
                            """, Map.of("id", actorId, "legacyId", legacyUserId, "role", role,
                                "name", clean(command.displayName())));
                    }
                    jdbc.update("""
                        INSERT INTO professional_profiles(professional_id, display_name, email, specialty, role_code, active)
                        VALUES (:id, :displayName, :email, :specialty, :role, true)
                        """, new MapSqlParameterSource().addValue("id", actorId).addValue("displayName", clean(command.displayName()))
                        .addValue("email", clean(command.email())).addValue("specialty", cleanNullable(command.specialty()))
                        .addValue("role", role));
                } else {
                    int updated = jdbc.update("""
                        UPDATE professional_profiles SET display_name = :displayName, email = :email,
                            specialty = :specialty, role_code = :role, active = :active,
                            version = version + 1, updated_at = now()
                         WHERE professional_id = :id AND version = :version
                        """, new MapSqlParameterSource().addValue("id", actorId).addValue("displayName", clean(command.displayName()))
                        .addValue("email", clean(command.email())).addValue("specialty", cleanNullable(command.specialty()))
                        .addValue("role", role).addValue("active", command.active()).addValue("version", command.expectedVersion()));
                    if (updated != 1) throw new VersionConflictException("El perfil cambió");
                    jdbc.update("UPDATE actors SET display_name = :displayName, email_hash = :emailHash, updated_at = now() WHERE actor_id = :id",
                        Map.of("id", actorId, "displayName", clean(command.displayName()),
                            "emailHash", sha256(clean(command.email()).toLowerCase())));
                    jdbc.update("UPDATE actors SET role_code = :role WHERE actor_id = :id", Map.of("id", actorId, "role", role));
                    if (account != null) {
                        jdbc.update("UPDATE actors SET legacy_user_id = COALESCE(legacy_user_id, :legacyId) WHERE actor_id = :id",
                            Map.of("id", actorId, "legacyId", account.userId()));
                    }
                }
                syncProfessionalRole(command.processId(), actorId, definition, admin.id());
                audit(admin.id(), "PROFESSIONAL_SAVED", "PROFESSIONAL", actorId, Map.of("role", role));
                return professional(actorId);
            });
        } catch (RuntimeException exception) {
            if (account != null) professionalAccounts.rollback(account);
            throw exception;
        }
    }

    public PasswordUpdateResult updateProfessionalPassword(UUID professionalId, String password) {
        PrekinderActor admin = access.requireAdmin();
        if (password == null || password.length() < 6 || password.length() > 128) {
            throw new IllegalArgumentException("La contraseña debe tener entre 6 y 128 caracteres");
        }
        Long legacyUserId = jdbc.queryForObject("""
            SELECT a.legacy_user_id
              FROM professional_profiles p JOIN actors a ON a.actor_id = p.professional_id
             WHERE p.professional_id = :id
            """, Map.of("id", professionalId), Long.class);
        if (legacyUserId == null) {
            throw PrekinderDomainException.conflict("PROFESSIONAL_ACCOUNT_NOT_FOUND",
                "El profesional no tiene una cuenta de acceso enlazada.");
        }
        professionalAccounts.updatePassword(legacyUserId, password);
        audit(admin.id(), "PROFESSIONAL_PASSWORD_UPDATED", "PROFESSIONAL", professionalId, Map.of());
        return new PasswordUpdateResult(professionalId, true);
    }

    public ProfessionalDeletionResult deleteProfessional(UUID professionalId, long expectedVersion) {
        PrekinderActor admin = access.requireAdmin();
        Long legacyUserId = transactions.execute(status -> {
            Map<String, Object> profile = jdbc.queryForMap("""
                SELECT p.version, a.legacy_user_id
                  FROM professional_profiles p JOIN actors a ON a.actor_id = p.professional_id
                 WHERE p.professional_id = :id
                 FOR UPDATE
                """, Map.of("id", professionalId));
            long currentVersion = ((Number) profile.get("version")).longValue();
            if (currentVersion != expectedVersion) throw new VersionConflictException("El perfil cambió");

            Long activeGroups = jdbc.queryForObject("""
                SELECT count(*)
                  FROM group_evaluator_assignments assignment
                  JOIN evaluation_groups evaluation_group ON evaluation_group.group_id = assignment.group_id
                 WHERE assignment.evaluator_id = :id
                   AND assignment.status = 'ACTIVE'
                   AND evaluation_group.status IN ('DRAFT','CONFIRMED','IN_PROGRESS')
                """, Map.of("id", professionalId), Long.class);
            if (activeGroups != null && activeGroups > 0) {
                throw PrekinderDomainException.conflict("PROFESSIONAL_HAS_ACTIVE_GROUPS",
                    "No puedes eliminar al profesional mientras tenga grupos activos asignados.");
            }

            audit(admin.id(), "PROFESSIONAL_DELETED", "PROFESSIONAL", professionalId, Map.of());
            jdbc.update("""
                UPDATE prekinder_actor_role_assignments
                   SET active = false, valid_until = now(), version = version + 1
                 WHERE actor_id = :id AND active
                """, Map.of("id", professionalId));
            jdbc.update("DELETE FROM professional_instrument_authorizations WHERE professional_id = :id",
                Map.of("id", professionalId));
            jdbc.update("DELETE FROM professional_availability WHERE professional_id = :id",
                Map.of("id", professionalId));
            jdbc.update("DELETE FROM professional_profiles WHERE professional_id = :id",
                Map.of("id", professionalId));
            jdbc.update("""
                UPDATE actors SET active = false, role_code = 'DELETED_PREKINDER', updated_at = now()
                 WHERE actor_id = :id
                """, Map.of("id", professionalId));
            return (Long) profile.get("legacy_user_id");
        });
        boolean accountDeleted = professionalAccounts.deleteExclusiveAccount(legacyUserId);
        return new ProfessionalDeletionResult(professionalId, true, accountDeleted);
    }

    private void syncProfessionalRole(UUID processId, UUID professionalId,
                                      ProfessionalRoleDefinition definition, UUID adminId) {
        jdbc.update("""
            UPDATE prekinder_actor_role_assignments
               SET active = false, valid_until = now(), version = version + 1
             WHERE process_id = :processId AND actor_id = :actorId AND active
            """, Map.of("processId", processId, "actorId", professionalId));
        jdbc.update("""
            INSERT INTO prekinder_actor_role_assignments(assignment_id, process_id, actor_id, role_code, assigned_by)
            VALUES (:id, :processId, :actorId, :role, :adminId)
            """, Map.of("id", UUID.randomUUID(), "processId", processId, "actorId", professionalId,
                "role", definition.roleCode(), "adminId", adminId));

        jdbc.update("""
            UPDATE professional_instrument_authorizations
               SET active = false, valid_until = now(), version = version + 1
             WHERE process_id = :processId AND professional_id = :professionalId AND active
            """, Map.of("processId", processId, "professionalId", professionalId));
        if (definition.instrumentCode() != null) {
            jdbc.update("""
                INSERT INTO professional_instrument_authorizations(authorization_id, process_id, professional_id,
                    instrument_code, authorized_by)
                VALUES (:id, :processId, :professionalId, :instrumentCode, :adminId)
                """, Map.of("id", UUID.randomUUID(), "processId", processId, "professionalId", professionalId,
                    "instrumentCode", definition.instrumentCode(), "adminId", adminId));
        }
    }

    public List<RoomView> rooms(UUID processId) {
        access.requireAdmin();
        return jdbc.query("""
            SELECT room_id, process_id, code, name, capacity, active, version
              FROM prekinder_rooms WHERE process_id = :processId ORDER BY code
            """, Map.of("processId", processId), (rs, row) -> new RoomView(rs.getObject("room_id", UUID.class),
                rs.getObject("process_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getInt("capacity"), rs.getBoolean("active"), rs.getLong("version")));
    }

    public RoomView createRoom(UUID processId, String code, String name, int capacity) {
        PrekinderActor actor = access.requireAdmin();
        if (capacity < 9) throw new IllegalArgumentException("La sala debe admitir al menos 9 postulantes");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO prekinder_rooms(room_id, process_id, code, name, capacity)
            VALUES (:id, :processId, :code, :name, :capacity)
            """, Map.of("id", id, "processId", processId, "code", clean(code).toUpperCase(),
                "name", clean(name), "capacity", capacity));
        audit(actor.id(), "ROOM_CREATED", "ROOM", id, Map.of());
        return room(id);
    }

    public GroupView createGroup(GroupCommand command) {
        PrekinderActor actor = access.requireAdmin();
        String stage = command.stage();
        if (!List.of("GROUP_3", "GROUP_9").contains(stage)) throw new IllegalArgumentException("Instancia inválida");
        int suggestedCapacity = stage.equals("GROUP_3") ? 3 : 9;
        int suggestedEvaluators = stage.equals("GROUP_3") ? 3 : 6;
        int capacity = command.capacity() == null ? suggestedCapacity : command.capacity();
        int requiredEvaluators = command.requiredEvaluators() == null ? suggestedEvaluators : command.requiredEvaluators();
        if (capacity < 1 || capacity > 30) throw new IllegalArgumentException("La capacidad debe estar entre 1 y 30");
        if (requiredEvaluators < 1 || requiredEvaluators > 12) throw new IllegalArgumentException("La cantidad de evaluadores debe estar entre 1 y 12");
        Integer roomCapacity = jdbc.queryForObject("SELECT capacity FROM prekinder_rooms WHERE room_id = :id AND process_id = :processId AND active = true",
            Map.of("id", command.roomId(), "processId", command.processId()), Integer.class);
        if (roomCapacity == null || capacity > roomCapacity) {
            throw PrekinderDomainException.conflict("ROOM_CAPACITY", "La capacidad configurada supera la capacidad física de la sala");
        }
        int duration = command.durationMinutes() == null ? 30 : command.durationMinutes();
        if (duration < 10 || duration > 240) throw new IllegalArgumentException("Duración fuera de rango");
        Instant endsAt = command.startsAt().plus(Duration.ofMinutes(duration));
        ScheduleSlot slot = ensureScheduleSlot(command.processId(), command.startsAt(), endsAt);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO evaluation_groups(group_id, process_id, day_id, block_id, room_id, stage, code, starts_at, ends_at,
                    capacity, required_evaluators, admin_capacity_override, admin_evaluator_override)
                VALUES (:id, :processId, :dayId, :blockId, :roomId, :stage, :code, :startsAt, :endsAt,
                    :suggestedCapacity, :suggestedEvaluators, :capacity, :evaluators)
                """, new MapSqlParameterSource().addValue("id", id).addValue("processId", command.processId())
                .addValue("dayId", slot.dayId()).addValue("blockId", slot.blockId())
                .addValue("roomId", command.roomId()).addValue("stage", stage).addValue("code", clean(command.code()))
                .addValue("startsAt", Timestamp.from(command.startsAt())).addValue("endsAt", Timestamp.from(endsAt))
                .addValue("suggestedCapacity", suggestedCapacity).addValue("suggestedEvaluators", suggestedEvaluators)
                .addValue("capacity", capacity).addValue("evaluators", requiredEvaluators));
        } catch (DataIntegrityViolationException exception) {
            throw PrekinderDomainException.conflict("SCHEDULE_CONFLICT", "La sala o el código ya están ocupados");
        }
        audit(actor.id(), "GROUP_CREATED", "GROUP", id, Map.of("stage", stage));
        return group(id);
    }

    public GroupView createAssignedGroup(GroupCommand command, List<UUID> memberIds, List<UUID> evaluatorIds) {
        List<UUID> children = memberIds == null ? List.of() : List.copyOf(memberIds);
        List<UUID> evaluators = evaluatorIds == null ? List.of() : List.copyOf(evaluatorIds);
        int capacity = command.capacity() == null ? ("GROUP_3".equals(command.stage()) ? 3 : 9) : command.capacity();
        int requiredEvaluators = command.requiredEvaluators() == null
            ? ("GROUP_3".equals(command.stage()) ? 3 : 6)
            : command.requiredEvaluators();

        if (children.isEmpty()) {
            throw new IllegalArgumentException("Selecciona al menos un postulante para formar el grupo");
        }
        if (children.size() > capacity) {
            throw new IllegalArgumentException("La cantidad de postulantes supera la capacidad del grupo");
        }
        if (children.stream().distinct().count() != children.size()) {
            throw new IllegalArgumentException("Un postulante no puede repetirse dentro del grupo");
        }
        if (evaluators.size() != requiredEvaluators) {
            throw new IllegalArgumentException("El equipo debe completar la cantidad de evaluadores requeridos");
        }
        if (evaluators.stream().distinct().count() != evaluators.size()) {
            throw new IllegalArgumentException("Un evaluador no puede repetirse dentro del equipo");
        }

        return transactions.execute(status -> {
            Long eligibleChildren = jdbc.queryForObject("""
                SELECT count(*) FROM applications
                 WHERE process_id = :processId
                   AND eligibility_status = 'VERIFIED'
                   AND application_id IN (:applicationIds)
                """, new MapSqlParameterSource().addValue("processId", command.processId())
                .addValue("applicationIds", children), Long.class);
            if (eligibleChildren == null || eligibleChildren != children.size()) {
                throw PrekinderDomainException.conflict("INELIGIBLE_MEMBER",
                    "Todos los postulantes deben pertenecer al proceso y tener su elegibilidad verificada");
            }
            GroupView created = createGroup(command);
            children.forEach(applicationId -> addMember(created.groupId(), applicationId));
            evaluators.forEach(evaluatorId -> assignEvaluator(created.groupId(), evaluatorId));
            return group(created.groupId());
        });
    }

    public GroupView rescheduleGroup(UUID groupId, UUID roomId, Instant startsAt, Integer durationMinutes,
                                     String reason, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        if (startsAt == null) throw new IllegalArgumentException("La nueva hora es obligatoria");
        int duration = durationMinutes == null ? 30 : durationMinutes;
        if (duration < 10 || duration > 240) throw new IllegalArgumentException("Duración fuera de rango");
        return transactions.execute(status -> {
            GroupView current = group(groupId);
            if (current.version() != expectedVersion) throw new VersionConflictException("El grupo cambió");
            boolean alreadyStarted = !Instant.now().isBefore(current.startsAt());
            if (alreadyStarted && blank(reason)) {
                throw new PrekinderDomainException("REASON_REQUIRED",
                    "Una reasignación durante o después del bloque requiere motivo", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Instant endsAt = startsAt.plus(Duration.ofMinutes(duration));
            ScheduleSlot slot = ensureScheduleSlot(current.processId(), startsAt, endsAt);
            if (alreadyStarted) return cloneForReschedule(actor, current, roomId, startsAt, endsAt, reason);
            jdbc.update("""
                UPDATE applicant_group_bookings SET active = false
                 WHERE member_id IN (SELECT member_id FROM evaluation_group_members WHERE group_id = :groupId)
                """, Map.of("groupId", groupId));
            jdbc.update("""
                UPDATE evaluator_group_bookings SET active = false
                 WHERE assignment_id IN (SELECT assignment_id FROM group_evaluator_assignments WHERE group_id = :groupId)
                """, Map.of("groupId", groupId));
            try {
                int updated = jdbc.update("""
                    UPDATE evaluation_groups SET room_id = :roomId, day_id = :dayId, block_id = :blockId,
                        starts_at = :startsAt, ends_at = :endsAt,
                        version = version + 1, updated_at = now()
                     WHERE group_id = :groupId AND version = :version AND status IN ('DRAFT','CONFIRMED')
                    """, new MapSqlParameterSource().addValue("roomId", roomId)
                    .addValue("dayId", slot.dayId()).addValue("blockId", slot.blockId())
                    .addValue("startsAt", Timestamp.from(startsAt)).addValue("endsAt", Timestamp.from(endsAt))
                    .addValue("groupId", groupId).addValue("version", expectedVersion));
                if (updated != 1) throw new VersionConflictException("El grupo cambió");
                jdbc.update("""
                    UPDATE applicant_group_bookings SET starts_at = :startsAt, ends_at = :endsAt, active = true
                     WHERE member_id IN (SELECT member_id FROM evaluation_group_members WHERE group_id = :groupId AND status IN ('ASSIGNED','ATTENDED'))
                    """, new MapSqlParameterSource().addValue("startsAt", Timestamp.from(startsAt))
                    .addValue("endsAt", Timestamp.from(endsAt)).addValue("groupId", groupId));
                jdbc.update("""
                    UPDATE evaluator_group_bookings SET starts_at = :startsAt, ends_at = :endsAt, active = true
                     WHERE assignment_id IN (SELECT assignment_id FROM group_evaluator_assignments WHERE group_id = :groupId AND status = 'ACTIVE')
                    """, new MapSqlParameterSource().addValue("startsAt", Timestamp.from(startsAt))
                    .addValue("endsAt", Timestamp.from(endsAt)).addValue("groupId", groupId));
            } catch (DataIntegrityViolationException exception) {
                throw PrekinderDomainException.conflict("SCHEDULE_CONFLICT", "La nueva sala, profesional o postulante tiene un conflicto de horario");
            }
            history(actor.id(), groupId, "TIME", groupId, "RESCHEDULED", reason);
            enqueueScheduleNotifications(groupId, "PREKINDER_GROUP_RESCHEDULED");
            return group(groupId);
        });
    }

    public GroupView configureGroup(UUID groupId, int capacity, int requiredEvaluators, String reason,
                                    long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        if (capacity < 1 || capacity > 30) throw new IllegalArgumentException("La capacidad debe estar entre 1 y 30");
        if (requiredEvaluators < 1 || requiredEvaluators > 12) throw new IllegalArgumentException("La cantidad de evaluadores debe estar entre 1 y 12");
        return transactions.execute(status -> {
            GroupView current = group(groupId);
            if (current.version() != expectedVersion) throw new VersionConflictException("El grupo cambió");
            if (current.status().equals("COMPLETED") || current.status().equals("CANCELLED")) {
                throw PrekinderDomainException.conflict("GROUP_LOCKED", "El grupo finalizado o cancelado no puede reconfigurarse");
            }
            if (!Instant.now().isBefore(current.startsAt()) && blank(reason)) {
                throw new PrekinderDomainException("REASON_REQUIRED",
                    "Una modificación durante o después del bloque requiere motivo", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (capacity < current.memberIds().size()) {
                throw PrekinderDomainException.conflict("GROUP_CAPACITY", "La capacidad no puede ser menor que los postulantes ya asignados");
            }
            if (requiredEvaluators < current.evaluatorIds().size()) {
                throw PrekinderDomainException.conflict("EVALUATOR_CAPACITY", "El equipo requerido no puede ser menor que los profesionales ya asignados");
            }
            Integer roomCapacity = jdbc.queryForObject("SELECT capacity FROM prekinder_rooms WHERE room_id = :id", Map.of("id", current.roomId()), Integer.class);
            if (roomCapacity == null || capacity > roomCapacity) {
                throw PrekinderDomainException.conflict("ROOM_CAPACITY", "La capacidad configurada supera la capacidad física de la sala");
            }
            int updated = jdbc.update("""
                UPDATE evaluation_groups SET admin_capacity_override = :capacity,
                    admin_evaluator_override = :evaluators, version = version + 1, updated_at = now()
                 WHERE group_id = :groupId AND version = :version
                """, Map.of("groupId", groupId, "version", expectedVersion, "capacity", capacity,
                    "evaluators", requiredEvaluators));
            if (updated != 1) throw new VersionConflictException("El grupo cambió");
            history(actor.id(), groupId, "GROUP", groupId, "LIMITS_CONFIGURED", reason);
            return group(groupId);
        });
    }

    private GroupView cloneForReschedule(PrekinderActor actor, GroupView current, UUID roomId,
                                         Instant startsAt, Instant endsAt, String reason) {
        UUID replacementId = UUID.randomUUID();
        ScheduleSlot slot = ensureScheduleSlot(current.processId(), startsAt, endsAt);
        String replacementCode = current.code() + "-R" + replacementId.toString().substring(0, 4).toUpperCase();
        try {
            jdbc.update("""
                INSERT INTO evaluation_groups(group_id, process_id, day_id, block_id, room_id, stage, code, starts_at, ends_at,
                    capacity, required_evaluators, admin_capacity_override, admin_evaluator_override, status)
                VALUES (:id, :processId, :dayId, :blockId, :roomId, :stage, :code, :startsAt, :endsAt,
                    :suggestedCapacity, :suggestedEvaluators, :capacity, :evaluators, 'DRAFT')
                """, new MapSqlParameterSource().addValue("id", replacementId)
                .addValue("processId", current.processId()).addValue("dayId", slot.dayId()).addValue("blockId", slot.blockId())
                .addValue("roomId", roomId).addValue("stage", current.stage())
                .addValue("code", replacementCode).addValue("startsAt", Timestamp.from(startsAt))
                .addValue("endsAt", Timestamp.from(endsAt))
                .addValue("suggestedCapacity", current.stage().equals("GROUP_3") ? 3 : 9)
                .addValue("suggestedEvaluators", current.stage().equals("GROUP_3") ? 3 : 6)
                .addValue("capacity", current.capacity()).addValue("evaluators", current.requiredEvaluators()));
            jdbc.update("UPDATE evaluator_reports SET status = 'SUPERSEDED', version = version + 1 WHERE group_id = :id AND status <> 'COMPLETED'",
                Map.of("id", current.groupId()));
            List<UUID> applications = new ArrayList<>(current.memberIds());
            List<UUID> evaluators = new ArrayList<>(current.evaluatorIds());
            jdbc.update("UPDATE applicant_group_bookings SET active = false WHERE member_id IN (SELECT member_id FROM evaluation_group_members WHERE group_id = :id)", Map.of("id", current.groupId()));
            jdbc.update("UPDATE evaluation_group_members SET status = 'MOVED', version = version + 1, updated_at = now() WHERE group_id = :id AND status IN ('ASSIGNED','ATTENDED')", Map.of("id", current.groupId()));
            jdbc.update("UPDATE evaluator_group_bookings SET active = false WHERE assignment_id IN (SELECT assignment_id FROM group_evaluator_assignments WHERE group_id = :id)", Map.of("id", current.groupId()));
            jdbc.update("UPDATE group_evaluator_assignments SET status = 'REPLACED', version = version + 1, ended_at = now() WHERE group_id = :id AND status = 'ACTIVE'", Map.of("id", current.groupId()));
            if (!current.status().equals("COMPLETED")) jdbc.update("UPDATE evaluation_groups SET status = 'CANCELLED', version = version + 1, updated_at = now() WHERE group_id = :id", Map.of("id", current.groupId()));
            for (UUID applicationId : applications) insertMember(replacementId, applicationId, startsAt, endsAt);
            for (UUID evaluatorId : evaluators) insertEvaluator(replacementId, evaluatorId, actor.id(), startsAt, endsAt);
        } catch (DataIntegrityViolationException exception) {
            throw PrekinderDomainException.conflict("SCHEDULE_CONFLICT", "La nueva sala, profesional o postulante tiene un conflicto de horario");
        }
        history(actor.id(), current.groupId(), "GROUP", current.groupId(), "SUPERSEDED", reason);
        history(actor.id(), replacementId, "GROUP", replacementId, "CREATED_FROM_RESCHEDULE", reason);
        enqueueScheduleNotifications(replacementId, "PREKINDER_GROUP_RESCHEDULED");
        return group(replacementId);
    }

    private void insertMember(UUID groupId, UUID applicationId, Instant startsAt, Instant endsAt) {
        UUID memberId = UUID.randomUUID();
        jdbc.update("INSERT INTO evaluation_group_members(member_id, group_id, application_id) VALUES (:id, :groupId, :applicationId)",
            Map.of("id", memberId, "groupId", groupId, "applicationId", applicationId));
        jdbc.update("INSERT INTO applicant_group_bookings(booking_id, member_id, application_id, starts_at, ends_at) VALUES (:id, :memberId, :applicationId, :startsAt, :endsAt)",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("memberId", memberId)
            .addValue("applicationId", applicationId).addValue("startsAt", Timestamp.from(startsAt)).addValue("endsAt", Timestamp.from(endsAt)));
    }

    private void insertEvaluator(UUID groupId, UUID evaluatorId, UUID actorId, Instant startsAt, Instant endsAt) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO group_evaluator_assignments(assignment_id, group_id, evaluator_id, assigned_by) VALUES (:id, :groupId, :evaluatorId, :actorId)",
            Map.of("id", assignmentId, "groupId", groupId, "evaluatorId", evaluatorId, "actorId", actorId));
        jdbc.update("INSERT INTO evaluator_group_bookings(booking_id, assignment_id, evaluator_id, starts_at, ends_at) VALUES (:id, :assignmentId, :evaluatorId, :startsAt, :endsAt)",
            new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("assignmentId", assignmentId)
            .addValue("evaluatorId", evaluatorId).addValue("startsAt", Timestamp.from(startsAt)).addValue("endsAt", Timestamp.from(endsAt)));
    }

    private ScheduleSlot ensureScheduleSlot(UUID processId, Instant startsAt, Instant endsAt) {
        LocalDate date = startsAt.atZone(SANTIAGO).toLocalDate();
        UUID dayId = jdbc.queryForObject("""
            INSERT INTO evaluation_days(day_id, process_id, day_date, name)
            VALUES (:id, :processId, :date, :name)
            ON CONFLICT (process_id, day_date) DO UPDATE SET name = evaluation_days.name
            RETURNING day_id
            """, Map.of("id", UUID.randomUUID(), "processId", processId, "date", date,
                "name", "Jornada " + date), UUID.class);
        UUID blockId = jdbc.queryForObject("""
            INSERT INTO evaluation_blocks(block_id, day_id, starts_at, ends_at, duration_minutes)
            VALUES (:id, :dayId, :startsAt, :endsAt, :duration)
            ON CONFLICT (day_id, starts_at, ends_at) DO UPDATE SET duration_minutes = evaluation_blocks.duration_minutes
            RETURNING block_id
            """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("dayId", dayId)
            .addValue("startsAt", Timestamp.from(startsAt)).addValue("endsAt", Timestamp.from(endsAt))
            .addValue("duration", Math.toIntExact(Duration.between(startsAt, endsAt).toMinutes())), UUID.class);
        return new ScheduleSlot(dayId, blockId);
    }

    private void enqueueScheduleNotifications(UUID groupId, String template) {
        jdbc.update("""
            INSERT INTO notification_intents(notification_id, application_id, template_code, channel, status,
                idempotency_key, payload)
            SELECT gen_random_uuid(), m.application_id, :template, 'EMAIL', 'PENDING',
                   :template || ':' || CAST(:groupId AS text) || ':family:' || CAST(m.application_id AS text)
                       || ':' || extract(epoch FROM g.starts_at)::bigint,
                   jsonb_build_object('groupCode', g.code, 'stage', g.stage, 'roomName', room.name,
                       'roomCode', room.code, 'startsAt', g.starts_at, 'endsAt', g.ends_at)
              FROM evaluation_group_members m JOIN evaluation_groups g ON g.group_id = m.group_id
              JOIN prekinder_rooms room ON room.room_id = g.room_id
             WHERE m.group_id = :groupId AND m.status = 'ASSIGNED'
            ON CONFLICT (idempotency_key) DO NOTHING
            """, Map.of("groupId", groupId, "template", template));
        jdbc.update("""
            INSERT INTO notification_intents(notification_id, recipient_actor_id, template_code, channel, status,
                idempotency_key, payload)
            SELECT gen_random_uuid(), e.evaluator_id, :template, 'EMAIL', 'PENDING',
                   :template || ':' || CAST(:groupId AS text) || ':professional:' || CAST(e.evaluator_id AS text)
                       || ':' || extract(epoch FROM g.starts_at)::bigint,
                   jsonb_build_object('groupCode', g.code, 'stage', g.stage, 'roomName', room.name,
                       'roomCode', room.code, 'startsAt', g.starts_at, 'endsAt', g.ends_at)
              FROM group_evaluator_assignments e JOIN evaluation_groups g ON g.group_id = e.group_id
              JOIN prekinder_rooms room ON room.room_id = g.room_id
             WHERE e.group_id = :groupId AND e.status = 'ACTIVE'
            ON CONFLICT (idempotency_key) DO NOTHING
            """, Map.of("groupId", groupId, "template", template));
    }

    private void enqueueApplicationScheduleNotification(UUID groupId, UUID applicationId, String template) {
        jdbc.update("""
            INSERT INTO notification_intents(notification_id, application_id, template_code, channel, status,
                idempotency_key, payload)
            SELECT gen_random_uuid(), :applicationId, :template, 'EMAIL', 'PENDING',
                   :template || ':' || CAST(:groupId AS text) || ':family:' || CAST(:applicationId AS text)
                       || ':' || extract(epoch FROM g.starts_at)::bigint,
                   jsonb_build_object('groupCode', g.code, 'stage', g.stage, 'roomName', room.name,
                       'roomCode', room.code, 'startsAt', g.starts_at, 'endsAt', g.ends_at)
              FROM evaluation_groups g JOIN prekinder_rooms room ON room.room_id = g.room_id
             WHERE g.group_id = :groupId
            ON CONFLICT (idempotency_key) DO NOTHING
            """, Map.of("groupId", groupId, "applicationId", applicationId, "template", template));
    }

    private void enqueueProfessionalScheduleNotification(UUID groupId, UUID evaluatorId, String template) {
        jdbc.update("""
            INSERT INTO notification_intents(notification_id, recipient_actor_id, template_code, channel, status,
                idempotency_key, payload)
            SELECT gen_random_uuid(), :evaluatorId, :template, 'EMAIL', 'PENDING',
                   :template || ':' || CAST(:groupId AS text) || ':professional:' || CAST(:evaluatorId AS text)
                       || ':' || extract(epoch FROM g.starts_at)::bigint,
                   jsonb_build_object('groupCode', g.code, 'stage', g.stage, 'roomName', room.name,
                       'roomCode', room.code, 'startsAt', g.starts_at, 'endsAt', g.ends_at)
              FROM evaluation_groups g JOIN prekinder_rooms room ON room.room_id = g.room_id
             WHERE g.group_id = :groupId
            ON CONFLICT (idempotency_key) DO NOTHING
            """, Map.of("groupId", groupId, "evaluatorId", evaluatorId, "template", template));
    }

    public List<GroupView> groups(UUID processId, LocalDate date) {
        access.requireAdmin();
        Instant from = date.atStartOfDay(SANTIAGO).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(SANTIAGO).toInstant();
        return jdbc.query("""
            SELECT group_id FROM evaluation_groups
             WHERE process_id = :processId AND starts_at >= :from AND starts_at < :to
             ORDER BY starts_at, code
            """, new MapSqlParameterSource().addValue("processId", processId)
            .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)),
            (rs, row) -> group(rs.getObject(1, UUID.class)));
    }

    public GroupView addMember(UUID groupId, UUID applicationId) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            GroupView group = group(groupId);
            if (group.memberIds().size() >= group.capacity()) {
                throw PrekinderDomainException.conflict("GROUP_CAPACITY", "El grupo alcanzó su capacidad máxima");
            }
            if (group.stage().equals("GROUP_9") && !firstStageComplete(applicationId)) {
                throw PrekinderDomainException.conflict("STAGE_ORDER", "La primera instancia aún no está completa");
            }
            UUID memberId = UUID.randomUUID();
            try {
                jdbc.update("""
                    INSERT INTO evaluation_group_members(member_id, group_id, application_id)
                    VALUES (:id, :groupId, :applicationId)
                    """, Map.of("id", memberId, "groupId", groupId, "applicationId", applicationId));
                jdbc.update("""
                    INSERT INTO applicant_group_bookings(booking_id, member_id, application_id, starts_at, ends_at)
                    VALUES (:id, :memberId, :applicationId, :startsAt, :endsAt)
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("memberId", memberId)
                    .addValue("applicationId", applicationId).addValue("startsAt", Timestamp.from(group.startsAt()))
                    .addValue("endsAt", Timestamp.from(group.endsAt())));
            } catch (DataIntegrityViolationException exception) {
                throw PrekinderDomainException.conflict("SCHEDULE_CONFLICT", "El postulante ya está asignado en ese horario");
            }
            history(actor.id(), groupId, "MEMBER", memberId, "ASSIGNED", null);
            createReportsForMemberIfNeeded(group, applicationId);
            enqueueApplicationScheduleNotification(groupId, applicationId, "PREKINDER_GROUP_ASSIGNED");
            return group(groupId);
        });
    }

    public GroupView assignEvaluator(UUID groupId, UUID evaluatorId) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            GroupView group = group(groupId);
            Long eligible = jdbc.queryForObject("""
                SELECT count(*)
                  FROM professional_profiles p
                  JOIN prekinder_actor_role_assignments r
                    ON r.actor_id = p.professional_id AND r.process_id = :processId AND r.active
                 WHERE p.professional_id = :evaluatorId AND p.active
                   AND p.role_code LIKE 'PK_EVALUATOR_%' AND r.role_code = p.role_code
                """, Map.of("processId", group.processId(), "evaluatorId", evaluatorId), Long.class);
            if (eligible == null || eligible == 0) {
                throw PrekinderDomainException.forbidden("PROFESSIONAL_ROLE_MISMATCH",
                    "El profesional no tiene un rol evaluador homologado para este proceso");
            }
            if (group.evaluatorIds().size() >= group.requiredEvaluators()) {
                throw PrekinderDomainException.conflict("EVALUATOR_CAPACITY", "El grupo ya tiene todos sus evaluadores");
            }
            UUID assignmentId = UUID.randomUUID();
            try {
                jdbc.update("""
                    INSERT INTO group_evaluator_assignments(assignment_id, group_id, evaluator_id, assigned_by)
                    VALUES (:id, :groupId, :evaluatorId, :assignedBy)
                    """, Map.of("id", assignmentId, "groupId", groupId, "evaluatorId", evaluatorId,
                    "assignedBy", actor.id()));
                jdbc.update("""
                    INSERT INTO evaluator_group_bookings(booking_id, assignment_id, evaluator_id, starts_at, ends_at)
                    VALUES (:id, :assignmentId, :evaluatorId, :startsAt, :endsAt)
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                    .addValue("assignmentId", assignmentId).addValue("evaluatorId", evaluatorId)
                    .addValue("startsAt", Timestamp.from(group.startsAt())).addValue("endsAt", Timestamp.from(group.endsAt())));
            } catch (DataIntegrityViolationException exception) {
                throw PrekinderDomainException.conflict("SCHEDULE_CONFLICT", "El profesional ya está asignado en ese horario");
            }
            history(actor.id(), groupId, "EVALUATOR", assignmentId, "ASSIGNED", null);
            createReportsForEvaluatorIfNeeded(group, evaluatorId);
            enqueueProfessionalScheduleNotification(groupId, evaluatorId, "PREKINDER_GROUP_ASSIGNED");
            return group(groupId);
        });
    }

    public GroupView confirmGroup(UUID groupId, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            GroupView current = group(groupId);
            if (current.memberIds().isEmpty()) throw PrekinderDomainException.conflict("GROUP_EMPTY", "Agrega postulantes antes de confirmar");
            if (current.evaluatorIds().size() != current.requiredEvaluators()) {
                throw PrekinderDomainException.conflict("EVALUATORS_REQUIRED", "Completa el equipo evaluador antes de confirmar");
            }
            int updated = jdbc.update("""
                UPDATE evaluation_groups SET status = 'CONFIRMED', version = version + 1, updated_at = now()
                 WHERE group_id = :groupId AND version = :version AND status = 'DRAFT'
                """, Map.of("groupId", groupId, "version", expectedVersion));
            if (updated != 1) throw new VersionConflictException("El grupo cambió");
            UUID templateVersionId = publishedTemplate(current.processId(), current.stage());
            for (UUID applicationId : current.memberIds()) {
                for (UUID evaluatorId : current.evaluatorIds()) {
                    jdbc.update("""
                        INSERT INTO evaluator_reports(report_id, group_id, application_id, evaluator_id,
                            evaluation_template_version_id)
                        VALUES (:id, :groupId, :applicationId, :evaluatorId, :templateVersionId)
                        ON CONFLICT (group_id, application_id, evaluator_id) DO NOTHING
                        """, Map.of("id", UUID.randomUUID(), "groupId", groupId, "applicationId", applicationId,
                        "evaluatorId", evaluatorId, "templateVersionId", templateVersionId));
                }
            }
            history(actor.id(), groupId, "GROUP", groupId, "CONFIRMED", null);
            return group(groupId);
        });
    }

    private void createReportsForMemberIfNeeded(GroupView group, UUID applicationId) {
        if (!List.of("CONFIRMED", "IN_PROGRESS").contains(group.status())) return;
        UUID templateVersionId = publishedTemplate(group.processId(), group.stage());
        for (UUID evaluatorId : group.evaluatorIds()) {
            jdbc.update("""
                INSERT INTO evaluator_reports(report_id, group_id, application_id, evaluator_id,
                    evaluation_template_version_id)
                VALUES (:id, :groupId, :applicationId, :evaluatorId, :templateVersionId)
                ON CONFLICT (group_id, application_id, evaluator_id) DO NOTHING
                """, Map.of("id", UUID.randomUUID(), "groupId", group.groupId(), "applicationId", applicationId,
                "evaluatorId", evaluatorId, "templateVersionId", templateVersionId));
        }
    }

    private void createReportsForEvaluatorIfNeeded(GroupView group, UUID evaluatorId) {
        if (!List.of("CONFIRMED", "IN_PROGRESS").contains(group.status())) return;
        UUID templateVersionId = publishedTemplate(group.processId(), group.stage());
        for (UUID applicationId : group.memberIds()) {
            jdbc.update("""
                INSERT INTO evaluator_reports(report_id, group_id, application_id, evaluator_id,
                    evaluation_template_version_id)
                VALUES (:id, :groupId, :applicationId, :evaluatorId, :templateVersionId)
                ON CONFLICT (group_id, application_id, evaluator_id) DO NOTHING
                """, Map.of("id", UUID.randomUUID(), "groupId", group.groupId(), "applicationId", applicationId,
                "evaluatorId", evaluatorId, "templateVersionId", templateVersionId));
        }
    }

    public GroupView completeGroup(UUID groupId, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        long pending = jdbc.queryForObject("""
            SELECT count(*) FROM evaluator_reports WHERE group_id = :groupId AND status <> 'COMPLETED'
            """, Map.of("groupId", groupId), Long.class);
        if (pending > 0) throw PrekinderDomainException.conflict("REPORTS_PENDING", "Aún existen informes sin completar");
        int updated = jdbc.update("""
            UPDATE evaluation_groups SET status = 'COMPLETED', version = version + 1, updated_at = now()
             WHERE group_id = :groupId AND version = :version AND status IN ('CONFIRMED','IN_PROGRESS')
            """, Map.of("groupId", groupId, "version", expectedVersion));
        if (updated != 1) throw new VersionConflictException("El grupo cambió");
        audit(actor.id(), "GROUP_COMPLETED", "GROUP", groupId, Map.of());
        return group(groupId);
    }

    public List<AgendaGroupView> myAgenda(LocalDate date) {
        PrekinderActor actor = access.requireEvaluator();
        Instant from = date.atStartOfDay(SANTIAGO).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(SANTIAGO).toInstant();
        return jdbc.query("""
            SELECT DISTINCT g.group_id FROM evaluation_groups g
              JOIN group_evaluator_assignments ge ON ge.group_id = g.group_id
             WHERE ge.evaluator_id = :actorId AND ge.status = 'ACTIVE'
               AND g.starts_at >= :from AND g.starts_at < :to AND g.status <> 'CANCELLED'
             ORDER BY g.group_id
            """, new MapSqlParameterSource().addValue("actorId", actor.id())
            .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)),
            (rs, row) -> agendaGroup(rs.getObject(1, UUID.class), actor.id()));
    }

    public DecisionView decide(UUID applicationId, String decision, String note) {
        PrekinderActor actor = access.requireAdmin();
        if (!List.of("ACCEPTED", "REJECTED", "WAITLIST").contains(decision)) throw new IllegalArgumentException("Decisión inválida");
        return transactions.execute(status -> {
            Long published = jdbc.queryForObject("SELECT count(*) FROM application_decisions_v2 WHERE application_id = :id AND status = 'PUBLISHED'",
                Map.of("id", applicationId), Long.class);
            if (published != null && published > 0) throw PrekinderDomainException.conflict("DECISION_LOCKED", "La decisión publicada requiere una rectificación autorizada");
            Integer version = jdbc.queryForObject("SELECT coalesce(max(version), 0) + 1 FROM application_decisions_v2 WHERE application_id = :id",
                Map.of("id", applicationId), Integer.class);
            UUID id = UUID.randomUUID();
            EncryptedPayload encrypted = encryption.encrypt(note == null ? "" : note.trim(),
                "prekinder|decision|" + id + "|application:" + applicationId);
            jdbc.update("UPDATE application_decisions_v2 SET status = 'CORRECTED' WHERE application_id = :id AND status = 'DRAFT'",
                Map.of("id", applicationId));
            jdbc.update("""
                INSERT INTO application_decisions_v2(decision_id, application_id, decision, note_ciphertext,
                    note_iv, note_wrapped_dek, note_wrapped_dek_iv, note_key_version, version, decided_by)
                VALUES (:id, :applicationId, :decision, :ciphertext, :iv, :wrappedDek, :wrappedDekIv,
                    :keyVersion, :version, :actorId)
                """, encryptedValues(encrypted).addValue("id", id).addValue("applicationId", applicationId)
                .addValue("decision", decision).addValue("version", version).addValue("actorId", actor.id()));
            audit(actor.id(), "DECISION_SAVED", "APPLICATION", applicationId, Map.of("decision", decision));
            return decision(id);
        });
    }

    public DecisionView correctPublishedDecision(UUID applicationId, String decision, String note, String reason) {
        PrekinderActor actor = access.requireSuperAdmin();
        if (!List.of("ACCEPTED", "REJECTED", "WAITLIST").contains(decision)) throw new IllegalArgumentException("Decisión inválida");
        if (blank(reason)) throw new IllegalArgumentException("La rectificación requiere motivo");
        return transactions.execute(status -> {
            UUID previousId = jdbc.queryForObject("""
                SELECT decision_id FROM application_decisions_v2
                 WHERE application_id = :id AND status = 'PUBLISHED' FOR UPDATE
                """, Map.of("id", applicationId), UUID.class);
            if (previousId == null) throw PrekinderDomainException.conflict("DECISION_NOT_PUBLISHED", "No existe una decisión publicada para rectificar");
            Integer version = jdbc.queryForObject("SELECT coalesce(max(version), 0) + 1 FROM application_decisions_v2 WHERE application_id = :id",
                Map.of("id", applicationId), Integer.class);
            UUID id = UUID.randomUUID();
            EncryptedPayload encryptedNote = encryption.encrypt(note == null ? "" : note.trim(),
                "prekinder|decision|" + id + "|application:" + applicationId);
            EncryptedPayload encryptedReason = encryption.encrypt(reason.trim(),
                "prekinder|decision-correction|" + id + "|application:" + applicationId);
            MapSqlParameterSource values = encryptedValues(encryptedNote).addValue("id", id)
                .addValue("applicationId", applicationId).addValue("decision", decision).addValue("version", version)
                .addValue("actorId", actor.id()).addValue("previousId", previousId);
            encryptedValues(encryptedReason, values, "reason");
            jdbc.update("UPDATE application_decisions_v2 SET status = 'CORRECTED' WHERE decision_id = :id", Map.of("id", previousId));
            jdbc.update("""
                INSERT INTO application_decisions_v2(decision_id, application_id, decision, note_ciphertext,
                    note_iv, note_wrapped_dek, note_wrapped_dek_iv, note_key_version, version, decided_by,
                    correction_of, correction_reason_ciphertext, correction_reason_iv,
                    correction_reason_wrapped_dek, correction_reason_wrapped_dek_iv, correction_reason_key_version)
                VALUES (:id, :applicationId, :decision, :ciphertext, :iv, :wrappedDek, :wrappedDekIv,
                    :keyVersion, :version, :actorId, :previousId, :reasonCiphertext, :reasonIv,
                    :reasonWrappedDek, :reasonWrappedDekIv, :reasonKeyVersion)
                """, values);
            audit(actor.id(), "PUBLISHED_DECISION_CORRECTED", "APPLICATION", applicationId,
                Map.of("previousDecisionId", previousId));
            return decision(id);
        });
    }

    public BatchView schedulePublication(UUID processId, Instant scheduledAt) {
        PrekinderActor actor = access.requireAdmin();
        if (scheduledAt == null || !scheduledAt.isAfter(Instant.now())) throw new IllegalArgumentException("La publicación debe programarse a futuro");
        return transactions.execute(status -> {
            UUID batchId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO publication_batches(batch_id, process_id, scheduled_at, created_by)
                VALUES (:id, :processId, :scheduledAt, :actorId)
                """, new MapSqlParameterSource().addValue("id", batchId).addValue("processId", processId)
                .addValue("scheduledAt", Timestamp.from(scheduledAt)).addValue("actorId", actor.id()));
            List<Map<String, Object>> decisions = jdbc.queryForList("""
                SELECT d.decision_id, d.application_id, d.decision, d.version, (d.correction_of IS NOT NULL) AS rectification
                  FROM application_decisions_v2 d JOIN applications a ON a.application_id = d.application_id
                 WHERE a.process_id = :processId AND d.status = 'DRAFT'
                """, Map.of("processId", processId));
            for (Map<String, Object> row : decisions) {
                UUID decisionId = (UUID) row.get("decision_id");
                UUID applicationId = (UUID) row.get("application_id");
                jdbc.update("""
                    INSERT INTO publication_batch_items(item_id, batch_id, application_id, decision_id, decision_snapshot)
                    VALUES (:id, :batchId, :applicationId, :decisionId, CAST(:snapshot AS jsonb))
                    """, Map.of("id", UUID.randomUUID(), "batchId", batchId, "applicationId", applicationId,
                    "decisionId", decisionId, "snapshot", json(Map.of("decision", row.get("decision"), "version", row.get("version"),
                        "rectification", row.get("rectification")))));
                jdbc.update("UPDATE application_decisions_v2 SET status = 'SCHEDULED' WHERE decision_id = :id", Map.of("id", decisionId));
            }
            audit(actor.id(), "PUBLICATION_SCHEDULED", "PUBLICATION_BATCH", batchId,
                Map.of("items", decisions.size()));
            return batch(batchId);
        });
    }

    public Map<String, Object> dashboard(UUID processId) {
        access.requireAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applications", jdbc.queryForObject("SELECT count(*) FROM applications WHERE process_id = :id",
            Map.of("id", processId), Long.class));
        result.put("eligibilityPending", jdbc.queryForObject("SELECT count(*) FROM applications WHERE process_id = :id AND eligibility_status = 'PENDING'",
            Map.of("id", processId), Long.class));
        result.put("groupsToday", jdbc.queryForObject("SELECT count(*) FROM evaluation_groups WHERE process_id = :id AND starts_at::date = (now() AT TIME ZONE 'America/Santiago')::date",
            Map.of("id", processId), Long.class));
        result.put("reportsPending", jdbc.queryForObject("""
            SELECT count(*) FROM evaluator_reports r JOIN evaluation_groups g ON g.group_id = r.group_id
             WHERE g.process_id = :id AND r.status <> 'COMPLETED'
            """, Map.of("id", processId), Long.class));
        result.put("decisionsReady", jdbc.queryForObject("""
            SELECT count(*) FROM application_decisions_v2 d JOIN applications a ON a.application_id = d.application_id
             WHERE a.process_id = :id AND d.status IN ('DRAFT','SCHEDULED')
            """, Map.of("id", processId), Long.class));
        return result;
    }

    public List<RubricView> rubrics(UUID processId) {
        access.requireAdmin();
        return jdbc.query("""
            SELECT t.evaluation_template_id, t.type_code, t.name, v.evaluation_template_version_id,
                   v.version, v.status, v.maximum_score, v.published_at, count(c.criterion_id) AS criteria_count
              FROM evaluation_templates t JOIN evaluation_template_versions v ON v.evaluation_template_id = t.evaluation_template_id
              LEFT JOIN evaluation_criteria c ON c.evaluation_template_version_id = v.evaluation_template_version_id
             WHERE t.process_id = :processId
             GROUP BY t.evaluation_template_id, v.evaluation_template_version_id
             ORDER BY t.type_code, v.version DESC
            """, Map.of("processId", processId), (rs, row) -> new RubricView(
                rs.getObject("evaluation_template_id", UUID.class), rs.getString("type_code"), rs.getString("name"),
                rs.getObject("evaluation_template_version_id", UUID.class), rs.getInt("version"), rs.getString("status"),
                rs.getBigDecimal("maximum_score"), instant(rs.getTimestamp("published_at")), rs.getInt("criteria_count")));
    }

    public List<AuditView> auditTrail(int limit) {
        access.requireAdmin();
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query("""
            SELECT audit_id, actor_id, action, aggregate_type, aggregate_id, result, occurred_at
              FROM audit_events ORDER BY occurred_at DESC LIMIT :limit
            """, Map.of("limit", safeLimit), (rs, row) -> new AuditView(rs.getObject("audit_id", UUID.class),
                rs.getObject("actor_id", UUID.class), rs.getString("action"), rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class), rs.getString("result"), instant(rs.getTimestamp("occurred_at"))));
    }

    public List<PublishedResultView> myPublishedResults() {
        PrekinderActor actor = access.requireActor();
        return jdbc.query("""
            SELECT a.application_id, a.applicant_id, i.decision_snapshot ->> 'decision' AS decision,
                   i.published_at, d.version
              FROM families f JOIN applicants ap ON ap.family_id = f.family_id
              JOIN applications a ON a.applicant_id = ap.applicant_id
              JOIN publication_batch_items i ON i.application_id = a.application_id
              JOIN application_decisions_v2 d ON d.decision_id = i.decision_id
             WHERE f.external_reference = :actorReference AND i.published_at IS NOT NULL
             ORDER BY i.published_at DESC
            """, Map.of("actorReference", actor.id().toString()), (rs, row) -> {
                UUID applicationId = rs.getObject("application_id", UUID.class);
                ApplicationView application = application(applicationId);
                String applicantName = (application.identity().firstName() + " "
                    + application.identity().paternalLastName()).trim();
                return new PublishedResultView(applicationId, applicantName, rs.getString("decision"),
                    instant(rs.getTimestamp("published_at")), rs.getInt("version"));
            });
    }

    private ApplicationView application(UUID id) {
        return jdbc.queryForObject("""
            SELECT a.application_id, a.applicant_id, a.process_id, a.wave_id, a.status,
                   a.eligibility_category, a.eligibility_status, a.version, a.created_at,
                   ap.identity_ciphertext, ap.identity_iv, ap.identity_wrapped_dek,
                   ap.identity_wrapped_dek_iv, ap.identity_key_version,
                   coalesce(ed.version, 0) AS declaration_version
              FROM applications a JOIN applicants ap ON ap.applicant_id = a.applicant_id
              LEFT JOIN eligibility_declarations ed ON ed.application_id = a.application_id
             WHERE a.application_id = :id
            """, Map.of("id", id), (rs, row) -> {
                UUID applicantId = rs.getObject("applicant_id", UUID.class);
                ApplicantIdentity identity = decryptIdentity(id, applicantId, new EncryptedPayload(
                    rs.getString("identity_ciphertext"), rs.getString("identity_iv"), rs.getString("identity_wrapped_dek"),
                    rs.getString("identity_wrapped_dek_iv"), rs.getString("identity_key_version")));
                return new ApplicationView(id, applicantId, rs.getObject("process_id", UUID.class),
                    rs.getObject("wave_id", UUID.class), rs.getString("status"), rs.getString("eligibility_category"),
                    rs.getString("eligibility_status"), rs.getLong("version"), rs.getLong("declaration_version"),
                    identity, applicationDetails(id), instant(rs.getTimestamp("created_at")));
            });
    }

    private ApplicationDetails applicationDetails(UUID applicationId) {
        List<ApplicationDetails> values = jdbc.query("""
            SELECT ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version
              FROM encrypted_field_values
             WHERE aggregate_type = 'APPLICATION' AND aggregate_id = :applicationId
               AND field_code = 'APPLICATION_FORM'
            """, Map.of("applicationId", applicationId), (rs, row) -> {
                EncryptedPayload payload = new EncryptedPayload(rs.getString("ciphertext"), rs.getString("iv"),
                    rs.getString("wrapped_dek"), rs.getString("wrapped_dek_iv"), rs.getString("key_version"));
                try {
                    return mapper.readValue(encryption.decrypt(payload,
                        "prekinder|application-form|application:" + applicationId + "|field:APPLICATION_FORM"),
                        ApplicationDetails.class);
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException("El formulario cifrado no tiene un formato válido", exception);
                }
            });
        return values.isEmpty() ? null : values.get(0);
    }

    private GroupView group(UUID id) {
        return jdbc.queryForObject("""
            SELECT g.group_id, g.process_id, g.room_id, r.name AS room_name, g.stage, g.code,
                   g.starts_at, g.ends_at, coalesce(g.admin_capacity_override, g.capacity) AS effective_capacity,
                   coalesce(g.admin_evaluator_override, g.required_evaluators) AS effective_evaluators,
                   g.status, g.version
              FROM evaluation_groups g JOIN prekinder_rooms r ON r.room_id = g.room_id
             WHERE g.group_id = :id
            """, Map.of("id", id), (rs, row) -> new GroupView(id, rs.getObject("process_id", UUID.class),
                rs.getObject("room_id", UUID.class), rs.getString("room_name"), rs.getString("stage"),
                rs.getString("code"), instant(rs.getTimestamp("starts_at")), instant(rs.getTimestamp("ends_at")),
                rs.getInt("effective_capacity"), rs.getInt("effective_evaluators"), rs.getString("status"), rs.getLong("version"),
                jdbc.queryForList("SELECT application_id FROM evaluation_group_members WHERE group_id = :id AND status IN ('ASSIGNED','ATTENDED') ORDER BY assigned_at",
                    Map.of("id", id), UUID.class),
                jdbc.queryForList("SELECT evaluator_id FROM group_evaluator_assignments WHERE group_id = :id AND status = 'ACTIVE' ORDER BY assigned_at",
                    Map.of("id", id), UUID.class)));
    }

    private AgendaGroupView agendaGroup(UUID groupId, UUID evaluatorId) {
        GroupView group = group(groupId);
        List<ReportSummary> reports = jdbc.query("""
            SELECT r.report_id, r.application_id, r.status, r.version, r.raw_score, r.maximum_score,
                   ap.identity_ciphertext, ap.identity_iv, ap.identity_wrapped_dek, ap.identity_wrapped_dek_iv,
                   ap.identity_key_version, a.applicant_id
              FROM evaluator_reports r JOIN applications a ON a.application_id = r.application_id
              JOIN applicants ap ON ap.applicant_id = a.applicant_id
             WHERE r.group_id = :groupId AND r.evaluator_id = :evaluatorId ORDER BY r.created_at
            """, Map.of("groupId", groupId, "evaluatorId", evaluatorId), (rs, row) -> {
                UUID applicationId = rs.getObject("application_id", UUID.class);
                UUID applicantId = rs.getObject("applicant_id", UUID.class);
                ApplicantIdentity identity = decryptIdentity(applicationId, applicantId, new EncryptedPayload(
                    rs.getString("identity_ciphertext"), rs.getString("identity_iv"), rs.getString("identity_wrapped_dek"),
                    rs.getString("identity_wrapped_dek_iv"), rs.getString("identity_key_version")));
                return new ReportSummary(rs.getObject("report_id", UUID.class), applicationId,
                    identity.firstName() + " " + identity.paternalLastName(), rs.getString("status"), rs.getLong("version"),
                    (java.math.BigDecimal) rs.getObject("raw_score"), (java.math.BigDecimal) rs.getObject("maximum_score"));
            });
        Instant now = Instant.now();
        return new AgendaGroupView(group, now.isAfter(group.startsAt().minus(Duration.ofMinutes(3)))
            && now.isBefore(group.endsAt().plus(Duration.ofMinutes(10))), reports);
    }

    private WaveView activeWave(UUID processId) {
        List<WaveView> active = waves(processId).stream().filter(WaveView::active).toList();
        if (active.isEmpty()) throw PrekinderDomainException.forbidden("NO_ACTIVE_WAVE", "No existe una etapa abierta en este momento");
        if (active.size() > 1) throw PrekinderDomainException.conflict("WAVE_CONFIGURATION", "Existe más de una etapa activa");
        return active.getFirst();
    }

    private WaveView wave(UUID id) {
        return jdbc.queryForObject("""
            SELECT wave_id, process_id, wave_type, position, status, opens_at, closes_at, version,
                   (status = 'PUBLISHED' AND opens_at <= now() AND closes_at >= now()) AS active
              FROM process_waves WHERE wave_id = :id
            """, Map.of("id", id), (rs, row) -> new WaveView(id, rs.getObject("process_id", UUID.class),
                rs.getString("wave_type"), rs.getInt("position"), rs.getString("status"),
                instant(rs.getTimestamp("opens_at")), instant(rs.getTimestamp("closes_at")),
                rs.getLong("version"), rs.getBoolean("active")));
    }

    private ProfessionalView professional(UUID id) {
        return jdbc.queryForObject("""
            SELECT p.professional_id, p.display_name, p.email, p.specialty, p.role_code, p.active, p.version,
                   a.legacy_user_id FROM professional_profiles p JOIN actors a ON a.actor_id = p.professional_id
             WHERE p.professional_id = :id
            """, Map.of("id", id), (rs, row) -> professionalView(id, (Long) rs.getObject("legacy_user_id"),
                rs.getString("display_name"), rs.getString("email"), rs.getString("specialty"),
                rs.getString("role_code"), rs.getBoolean("active"), rs.getLong("version")));
    }

    private static ProfessionalRoleDefinition professionalRole(String roleCode) {
        if (blank(roleCode)) throw new IllegalArgumentException("Selecciona el rol del profesional");
        return PROFESSIONAL_ROLES.stream().filter(item -> item.roleCode().equals(roleCode)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("El rol no pertenece al flujo Prekínder"));
    }

    private static ProfessionalView professionalView(UUID id, Long legacyUserId, String displayName, String email,
                                                       String specialty, String roleCode, boolean active, long version) {
        ProfessionalRoleDefinition definition = PROFESSIONAL_ROLES.stream()
            .filter(item -> item.roleCode().equals(roleCode)).findFirst().orElse(null);
        return new ProfessionalView(id, legacyUserId, displayName, email, specialty, roleCode,
            definition == null ? "Pendiente de homologación" : definition.label(),
            definition == null ? "PENDING" : definition.groupCode(),
            definition == null ? null : definition.instrumentCode(), active, version);
    }

    private RoomView room(UUID id) {
        return jdbc.queryForObject("""
            SELECT room_id, process_id, code, name, capacity, active, version FROM prekinder_rooms WHERE room_id = :id
            """, Map.of("id", id), (rs, row) -> new RoomView(id, rs.getObject("process_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getInt("capacity"), rs.getBoolean("active"), rs.getLong("version")));
    }

    private DecisionView decision(UUID id) {
        return jdbc.queryForObject("""
            SELECT decision_id, application_id, decision, version, status, decided_at
              FROM application_decisions_v2 WHERE decision_id = :id
            """, Map.of("id", id), (rs, row) -> new DecisionView(id, rs.getObject("application_id", UUID.class),
                rs.getString("decision"), rs.getInt("version"), rs.getString("status"), instant(rs.getTimestamp("decided_at"))));
    }

    private BatchView batch(UUID id) {
        return jdbc.queryForObject("""
            SELECT b.batch_id, b.process_id, b.scheduled_at, b.status, b.version, b.created_at,
                   count(i.item_id) AS item_count
              FROM publication_batches b LEFT JOIN publication_batch_items i ON i.batch_id = b.batch_id
             WHERE b.batch_id = :id GROUP BY b.batch_id
            """, Map.of("id", id), (rs, row) -> new BatchView(id, rs.getObject("process_id", UUID.class),
                instant(rs.getTimestamp("scheduled_at")), rs.getString("status"), rs.getLong("version"),
                rs.getLong("item_count"), instant(rs.getTimestamp("created_at"))));
    }

    private boolean firstStageComplete(UUID applicationId) {
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM evaluation_group_members m JOIN evaluation_groups g ON g.group_id = m.group_id
             WHERE m.application_id = :id AND g.stage = 'GROUP_3' AND g.status = 'COMPLETED'
            """, Map.of("id", applicationId), Long.class);
        return count != null && count > 0;
    }

    private UUID publishedTemplate(UUID processId, String stage) {
        List<UUID> ids = jdbc.queryForList("""
            SELECT v.evaluation_template_version_id FROM evaluation_template_versions v
              JOIN evaluation_templates t ON t.evaluation_template_id = v.evaluation_template_id
             WHERE t.process_id = :processId AND t.type_code = :stage AND v.status = 'PUBLISHED'
             ORDER BY v.version DESC LIMIT 1
            """, Map.of("processId", processId, "stage", stage), UUID.class);
        if (ids.isEmpty()) throw PrekinderDomainException.conflict("RUBRIC_MISSING", "No existe una pauta publicada para esta instancia");
        return ids.getFirst();
    }

    private void history(UUID actorId, UUID groupId, String entityType, UUID entityId, String action, String reason) {
        UUID historyId = UUID.randomUUID();
        MapSqlParameterSource values = new MapSqlParameterSource().addValue("id", historyId)
            .addValue("groupId", groupId).addValue("entityType", entityType).addValue("entityId", entityId)
            .addValue("action", action).addValue("actorId", actorId);
        if (!blank(reason)) {
            EncryptedPayload encryptedReason = encryption.encrypt(reason.trim(),
                "prekinder|assignment-history|" + historyId + "|reason");
            encryptedValues(encryptedReason, values, "reason");
        }
        ensureReasonValues(values);
        jdbc.update("""
            INSERT INTO group_assignment_history(history_id, group_id, entity_type, entity_id, action, actor_id,
                reason_ciphertext, reason_iv, reason_wrapped_dek, reason_wrapped_dek_iv, reason_key_version)
            VALUES (:id, :groupId, :entityType, :entityId, :action, :actorId,
                :reasonCiphertext, :reasonIv, :reasonWrappedDek, :reasonWrappedDekIv, :reasonKeyVersion)
            """, values);
    }

    private void audit(UUID actorId, String action, String aggregateType, UUID aggregateId, Map<String, ?> metadata) {
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result, metadata)
            VALUES (:id, :actorId, :action, :aggregateType, :aggregateId, 'SUCCESS', CAST(:metadata AS jsonb))
            """, Map.of("id", UUID.randomUUID(), "actorId", actorId, "action", action,
                "aggregateType", aggregateType, "aggregateId", aggregateId, "metadata", json(metadata)));
    }

    private ApplicantIdentity decryptIdentity(UUID applicationId, UUID applicantId, EncryptedPayload payload) {
        try {
            return mapper.readValue(encryption.decrypt(payload,
                "prekinder|applicants|" + applicantId + "|application:" + applicationId + "|identity"), ApplicantIdentity.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("La identidad cifrada no tiene un formato válido", exception);
        }
    }

    private static String category(EligibilityDeclaration eligibility) {
        if (eligibility == null) throw new IllegalArgumentException("La declaración de elegibilidad es obligatoria");
        if (eligibility.siblings() != null && !eligibility.siblings().isEmpty()) return "SIBLINGS";
        boolean employee = eligibility.employeeParent() != null && !eligibility.employeeParent().isBlank();
        boolean alumni = alumni(eligibility.fatherAlumni()) || alumni(eligibility.motherAlumni());
        return employee || alumni ? "STAFF_OR_ALUMNI" : "NEW_FAMILIES";
    }

    private void validateApplicationDetails(SubmitApplication command, String category) {
        ApplicationDetails details = command.applicationDetails();
        if (details == null) throw new IllegalArgumentException("Los antecedentes de la postulación son obligatorios");
        if (!"PRE_KINDER".equals(details.grade())) throw new IllegalArgumentException("El nivel debe ser Prekínder");
        Integer academicYear = jdbc.queryForObject(
            "SELECT academic_year FROM admission_processes WHERE process_id = :id",
            Map.of("id", command.processId()), Integer.class);
        if (academicYear == null || academicYear != details.applicationYear()) {
            throw new IllegalArgumentException("El año de postulación no corresponde al proceso vigente");
        }
        if (details.father() == null || details.mother() == null || details.supporter() == null || details.guardian() == null) {
            throw new IllegalArgumentException("Padre, madre, sostenedor y apoderado son obligatorios");
        }
        PrekinderRut.normalize(details.father().rut());
        PrekinderRut.normalize(details.mother().rut());
        PrekinderRut.normalize(details.supporter().rut());
        PrekinderRut.normalize(details.guardian().rut());
        if (command.eligibility().siblings() != null) {
            command.eligibility().siblings().forEach(sibling -> PrekinderRut.normalize(sibling.rut()));
        }
        switch (category) {
            case "SIBLINGS" -> {
                if (!details.hasSiblingsInSchool() || blank(details.siblingsInSchoolDetails())
                    || !"NINGUNA".equals(details.admissionPreference())) {
                    throw new IllegalArgumentException("Debes registrar el hermano vigente de esta etapa");
                }
            }
            case "STAFF_OR_ALUMNI" -> {
                boolean employee = !blank(command.eligibility().employeeParent());
                boolean alumni = alumni(command.eligibility().fatherAlumni()) || alumni(command.eligibility().motherAlumni());
                if (employee && !List.of("FATHER", "MOTHER", "BOTH").contains(command.eligibility().employeeParent())) {
                    throw new IllegalArgumentException("Padre o madre funcionario inválido");
                }
                if (employee == alumni) {
                    throw new IllegalArgumentException("Selecciona sólo un vínculo: funcionario o exalumno");
                }
                if (employee && !"HIJO_FUNCIONARIO".equals(details.admissionPreference())) {
                    throw new IllegalArgumentException("La relación familiar no coincide con el funcionario declarado");
                }
                if (alumni && !"HIJO_EX_ALUMNO".equals(details.admissionPreference())) {
                    throw new IllegalArgumentException("La relación familiar no coincide con el exalumno declarado");
                }
                if (details.hasSiblingsInSchool()) {
                    throw new IllegalArgumentException("La etapa vigente no corresponde a hermanos de alumnos");
                }
            }
            case "NEW_FAMILIES" -> {
                if (details.hasSiblingsInSchool() || !"NINGUNA".equals(details.admissionPreference())) {
                    throw new IllegalArgumentException("La etapa vigente corresponde a nuevas familias");
                }
            }
            default -> throw new IllegalArgumentException("Categoría de postulación inválida");
        }
    }

    private static boolean alumni(AlumniDeclaration declaration) {
        if (declaration == null || declaration.status() == null || declaration.status().equals("NO_ALUMNI")) return false;
        if (declaration.status().equals("GRADUATED") || declaration.status().equals("GRADUATED_4TH")) {
            if (declaration.graduationYear() == null) throw new IllegalArgumentException("Indica el año de egreso");
            return true;
        }
        if (declaration.status().equals("WITHDREW")) {
            if (blank(declaration.lastGrade()) || blank(declaration.withdrawalReason())) {
                throw new IllegalArgumentException("Indica último curso y motivo de retiro");
            }
            return true;
        }
        throw new IllegalArgumentException("Estado de exalumno inválido");
    }

    private static String waveLabel(String type) {
        return switch (type) {
            case "SIBLINGS" -> "hermanos de alumnos vigentes";
            case "STAFF_OR_ALUMNI" -> "hijos de funcionarios o exalumnos";
            default -> "nuevas familias";
        };
    }

    private MapSqlParameterSource encryptedValues(EncryptedPayload payload) {
        return new MapSqlParameterSource().addValue("ciphertext", payload.ciphertext()).addValue("iv", payload.iv())
            .addValue("wrappedDek", payload.wrappedDek()).addValue("wrappedDekIv", payload.wrappedDekIv())
            .addValue("keyVersion", payload.keyVersion());
    }

    private void encryptedValues(EncryptedPayload payload, MapSqlParameterSource values, String prefix) {
        values.addValue(prefix + "Ciphertext", payload.ciphertext()).addValue(prefix + "Iv", payload.iv())
            .addValue(prefix + "WrappedDek", payload.wrappedDek()).addValue(prefix + "WrappedDekIv", payload.wrappedDekIv())
            .addValue(prefix + "KeyVersion", payload.keyVersion());
    }

    private static MapSqlParameterSource ensureReasonValues(MapSqlParameterSource values) {
        for (String name : List.of("reasonCiphertext", "reasonIv", "reasonWrappedDek", "reasonWrappedDekIv", "reasonKeyVersion")) {
            if (!values.hasValue(name)) values.addValue(name, null);
        }
        return values;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Datos inválidos", exception); }
    }

    private static long identityLock(String rut) {
        byte[] digest = hexToBytes(sha256("prekinder-application|" + rut));
        return java.nio.ByteBuffer.wrap(digest).getLong();
    }

    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static byte[] hexToBytes(String value) { return java.util.HexFormat.of().parseHex(value); }
    private static String clean(String value) { if (blank(value)) throw new IllegalArgumentException("Campo obligatorio"); return value.trim(); }
    private static String cleanNullable(String value) { return value == null ? "" : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record WaveView(UUID waveId, UUID processId, String waveType, int position, String status,
                           Instant opensAt, Instant closesAt, long version, boolean active) {}
    public record SiblingDeclaration(String name, String rut, String currentGrade) {}
    public record AlumniDeclaration(String status, Integer graduationYear, String lastGrade, String withdrawalReason) {}
    public record EligibilityDeclaration(List<SiblingDeclaration> siblings, String employeeParent,
                                         AlumniDeclaration fatherAlumni, AlumniDeclaration motherAlumni) {}
    public record AddressDetails(String street, String number, String apartment, String country,
                                 String region, String commune) {}
    public record FamilyAdultDetails(String fullName, String rut, String email, String phone,
                                     String address, String profession) {}
    public record ResponsibleAdultDetails(String fullName, String rut, String email, String phone,
                                          String relationship) {}
    public record ApplicationDetails(String gender, String studentEmail, AddressDetails address, String grade,
                                     int applicationYear, String currentSchool, String additionalNotes,
                                     String admissionPreference, boolean hasSiblingsInSchool,
                                     String siblingsInSchoolDetails, FamilyAdultDetails father,
                                     FamilyAdultDetails mother, ResponsibleAdultDetails supporter,
                                     ResponsibleAdultDetails guardian) {}
    public record SubmitApplication(UUID processId, String rut, String firstName, String paternalLastName,
                                    String maternalLastName, LocalDate birthDate, String familyEmail,
                                    String fatherEmail, String motherEmail, ApplicationDetails applicationDetails,
                                    EligibilityDeclaration eligibility) {}
    public record ApplicantIdentity(String rut, String firstName, String paternalLastName,
                                    String maternalLastName, LocalDate birthDate, String familyEmail,
                                    String fatherEmail, String motherEmail) {}
    public record ApplicationView(UUID applicationId, UUID applicantId, UUID processId, UUID waveId,
                                  String status, String eligibilityCategory, String eligibilityStatus,
                                  long version, long declarationVersion, ApplicantIdentity identity,
                                  ApplicationDetails applicationDetails, Instant createdAt) {}
    public record ProfessionalCommand(UUID processId, UUID professionalId, Long legacyUserId, String displayName, String email,
                                      String password, String specialty, String roleCode, boolean active, long expectedVersion) {}
    public record ProfessionalRoleDefinition(String roleCode, String label, String groupCode,
                                             String instrumentCode, int position) {}
    public record ProfessionalView(UUID professionalId, Long legacyUserId, String displayName, String email,
                                   String specialty, String roleCode, String roleLabel, String roleGroup,
                                   String instrumentCode, boolean active, long version) {}
    public record PasswordUpdateResult(UUID professionalId, boolean passwordUpdated) {}
    public record ProfessionalDeletionResult(UUID professionalId, boolean deleted, boolean firebaseAccountDeleted) {}
    public record AvailabilityView(UUID availabilityId, UUID professionalId, Instant startsAt, Instant endsAt,
                                   String status, long version) {}
    public record ScheduleBlockView(UUID dayId, LocalDate date, String dayName, String dayStatus, long dayVersion,
                                    UUID blockId, Instant startsAt, Instant endsAt, int durationMinutes, long blockVersion) {}
    public record RoomView(UUID roomId, UUID processId, String code, String name, int capacity,
                           boolean active, long version) {}
    public record GroupCommand(UUID processId, UUID roomId, String stage, String code, Instant startsAt,
                               Integer durationMinutes, Integer capacity, Integer requiredEvaluators) {}
    public record GroupView(UUID groupId, UUID processId, UUID roomId, String roomName, String stage,
                            String code, Instant startsAt, Instant endsAt, int capacity, int requiredEvaluators,
                            String status, long version, List<UUID> memberIds, List<UUID> evaluatorIds) {}
    public record ReportSummary(UUID reportId, UUID applicationId, String applicantName, String status,
                                long version, java.math.BigDecimal rawScore, java.math.BigDecimal maximumScore) {}
    public record AgendaGroupView(GroupView group, boolean editableNow, List<ReportSummary> reports) {}
    public record DecisionView(UUID decisionId, UUID applicationId, String decision, int version,
                               String status, Instant decidedAt) {}
    public record BatchView(UUID batchId, UUID processId, Instant scheduledAt, String status,
                            long version, long itemCount, Instant createdAt) {}
    public record PublishedResultView(UUID applicationId, String applicantName, String decision,
                                      Instant publishedAt, int decisionVersion) {}
    public record RubricView(UUID rubricId, String stage, String name, UUID versionId, int version, String status,
                             java.math.BigDecimal maximumScore, Instant publishedAt, int criteriaCount) {}
    public record AuditView(UUID auditId, UUID actorId, String action, String aggregateType, UUID aggregateId,
                            String result, Instant occurredAt) {}
    private record ScheduleSlot(UUID dayId, UUID blockId) {}
}
