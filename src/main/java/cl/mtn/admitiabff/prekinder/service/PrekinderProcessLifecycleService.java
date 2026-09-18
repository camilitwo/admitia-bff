package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.domain.PrekinderPolicyCodes;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
public class PrekinderProcessLifecycleService {
    public static final List<String> REQUIRED_INSTRUMENTS = PrekinderPolicyCodes.REQUIRED_INSTRUMENTS;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final List<String> RESULT_EVENTS = List.of(
        "RESULT_ACCEPTED", "RESULT_WAITLIST", "RESULT_REJECTED", "RESULT_RECTIFICATION"
    );

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;

    public PrekinderProcessLifecycleService(
        @Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
        PrekinderAccessService access
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
    }

    public ProcessConfiguration configuration(UUID processId) {
        access.requireAdmin();
        return loadConfiguration(processId);
    }

    public ProcessConfiguration saveConfiguration(UUID processId, ConfigurationCommand command) {
        PrekinderActor actor = access.requireAdmin();
        validate(command);
        return transactions.execute(status -> {
            ProcessConfiguration current = loadConfiguration(processId);
            long applications = count("SELECT count(*) FROM applications WHERE process_id = :id", processId);
            long formalApplications = count(
                "SELECT count(*) FROM applications WHERE process_id = :id AND formal_submitted_at IS NOT NULL", processId);
            boolean paymentChanged = current.paymentEnabled() != command.paymentEnabled()
                || !java.util.Objects.equals(current.paymentAmount(), command.paymentAmount())
                || !current.paymentCurrency().equalsIgnoreCase(command.paymentCurrency())
                || current.paymentDueDays() != command.paymentDueDays();
            if (applications > 0 && paymentChanged) {
                throw PrekinderDomainException.conflict("PROCESS_PAYMENT_IN_USE",
                    "No se puede cambiar la política de pago cuando el proceso ya tiene postulaciones");
            }
            if (formalApplications > 0 && criticalPolicyChanged(current, command)) {
                throw PrekinderDomainException.conflict("PROCESS_POLICY_FROZEN",
                    "Crea una nueva versión de campaña: ya existen postulaciones formalizadas con esta política");
            }
            int updated = jdbc.update("""
                UPDATE prekinder_process_configuration
                   SET payment_enabled = :paymentEnabled, payment_amount = :paymentAmount,
                       payment_currency = :paymentCurrency, payment_glosa = :paymentGlosa,
                       payment_due_days = :paymentDueDays, inclusion_enabled = :inclusionEnabled,
                       inclusion_documents_required = :inclusionDocumentsRequired,
                       minimum_age_months = :minimumAgeMonths, maximum_age_months = :maximumAgeMonths,
                       age_reference_date = :ageReferenceDate,
                       applicant_weight = 1.0000, family_weight = 0.0000,
                       total_seats = :totalSeats, male_seats = :maleSeats, female_seats = :femaleSeats,
                       incorporation_fee_amount = :incorporationFeeAmount,
                       incorporation_fee_currency = :incorporationFeeCurrency,
                       incorporation_fee_glosa = :incorporationFeeGlosa,
                       required_documents = CAST(:requiredDocuments AS jsonb),
                       schedule_timezone = :scheduleTimezone, schedule_day_start = :scheduleDayStart,
                       schedule_day_end = :scheduleDayEnd, schedule_block_minutes = :scheduleBlockMinutes,
                       schedule_max_blocks = :scheduleMaxBlocks,
                       suggested_parallel_capacity = :suggestedParallelCapacity,
                       academic_group_size = :academicGroupSize,
                       psychomotor_group_size = :psychomotorGroupSize,
                       academic_required_evaluators = :academicRequiredEvaluators,
                       psychomotor_required_evaluators = :psychomotorRequiredEvaluators,
                       initial_offer_hours = :initialOfferHours,
                       waitlist_offer_hours = :waitlistOfferHours,
                       advisory_threshold = :advisoryThreshold,
                       result_channel = 'EMAIL_ONLY',
                       version = version + 1, updated_at = now()
                 WHERE process_id = :processId AND version = :expectedVersion
                """, new MapSqlParameterSource()
                .addValue("processId", processId)
                .addValue("paymentEnabled", command.paymentEnabled())
                .addValue("paymentAmount", command.paymentAmount())
                .addValue("paymentCurrency", command.paymentCurrency().trim().toUpperCase())
                .addValue("paymentGlosa", command.paymentGlosa().trim())
                .addValue("paymentDueDays", command.paymentDueDays())
                .addValue("inclusionEnabled", command.inclusionEnabled())
                .addValue("inclusionDocumentsRequired", command.inclusionDocumentsRequired())
                .addValue("minimumAgeMonths", command.minimumAgeMonths())
                .addValue("maximumAgeMonths", command.maximumAgeMonths())
                .addValue("ageReferenceDate", command.ageReferenceDate())
                .addValue("totalSeats", command.totalSeats())
                .addValue("maleSeats", command.maleSeats())
                .addValue("femaleSeats", command.femaleSeats())
                .addValue("incorporationFeeAmount", command.incorporationFeeAmount())
                .addValue("incorporationFeeCurrency", command.incorporationFeeCurrency().trim().toUpperCase())
                .addValue("incorporationFeeGlosa", command.incorporationFeeGlosa().trim())
                .addValue("requiredDocuments", writeJson(command.requiredDocuments()))
                .addValue("scheduleTimezone", command.scheduleTimezone())
                .addValue("scheduleDayStart", command.scheduleDayStart())
                .addValue("scheduleDayEnd", command.scheduleDayEnd())
                .addValue("scheduleBlockMinutes", command.scheduleBlockMinutes())
                .addValue("scheduleMaxBlocks", command.scheduleMaxBlocks())
                .addValue("suggestedParallelCapacity", command.suggestedParallelCapacity())
                .addValue("academicGroupSize", command.academicGroupSize())
                .addValue("psychomotorGroupSize", command.psychomotorGroupSize())
                .addValue("academicRequiredEvaluators", command.academicRequiredEvaluators())
                .addValue("psychomotorRequiredEvaluators", command.psychomotorRequiredEvaluators())
                .addValue("initialOfferHours", command.initialOfferHours())
                .addValue("waitlistOfferHours", command.waitlistOfferHours())
                .addValue("advisoryThreshold", command.advisoryThreshold())
                .addValue("expectedVersion", command.expectedVersion()));
            if (updated != 1) throw new VersionConflictException("La configuración del proceso cambió");
            synchronizeSeatLedger(processId, command.maleSeats(), command.femaleSeats());
            ProcessConfiguration saved = loadConfiguration(processId);
            jdbc.update("""
                INSERT INTO prekinder_process_configuration_versions(
                    configuration_version_id, process_id, version, snapshot, created_by)
                VALUES (:id, :processId, :version, CAST(:snapshot AS jsonb), :actorId)
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                .addValue("processId", processId).addValue("version", saved.version())
                .addValue("snapshot", configurationJson(saved)).addValue("actorId", actor.id()));
            jdbc.update("UPDATE scoring_policies SET status = 'SUPERSEDED' WHERE process_id = :id AND status = 'PUBLISHED'",
                Map.of("id", processId));
            Integer policyVersion = jdbc.queryForObject(
                "SELECT coalesce(max(version), 0) + 1 FROM scoring_policies WHERE process_id = :id",
                Map.of("id", processId), Integer.class);
            jdbc.update("""
                INSERT INTO scoring_policies(scoring_policy_id, process_id, version, status,
                    applicant_weight, family_weight, formula_document, published_at)
                VALUES (:id, :processId, :version, 'PUBLISHED', 1.000000, 0.000000,
                    CAST(:formula AS jsonb), now())
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("processId", processId)
                .addValue("version", policyVersion)
                .addValue("formula", "{\"formula\":\"ACADEMIC*0.34+PSYCHOLOGY*0.33+PSYCHOMOTOR*0.33\","
                    + "\"familyInterview\":\"QUALITATIVE\",\"automaticDecision\":false,"
                    + "\"configurationVersion\":" + saved.version() + "}"));
            audit(actor.id(), "PROCESS_CONFIGURATION_UPDATED", "PROCESS", processId);
            return saved;
        });
    }

    public ProcessSummary updateProcess(UUID processId, ProcessCommand command) {
        PrekinderActor actor = access.requireAdmin();
        if (command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("El nombre del proceso es obligatorio");
        }
        int updated = jdbc.update("""
            UPDATE admission_processes
               SET name = :name, academic_year = :academicYear,
                   version = version + 1, updated_at = now()
             WHERE process_id = :processId AND version = :expectedVersion AND status = 'DRAFT'
            """, Map.of("processId", processId, "name", command.name().trim(),
                "academicYear", command.academicYear(), "expectedVersion", command.expectedVersion()));
        if (updated != 1) throw new VersionConflictException("El proceso cambió o ya no admite edición general");
        audit(actor.id(), "PROCESS_UPDATED", "PROCESS", processId);
        return process(processId);
    }

    public ProcessSummary close(UUID processId, long expectedVersion) {
        access.requireAdmin();
        long activePublications = count("""
            SELECT count(*) FROM publication_batches WHERE process_id = :id
             AND status IN ('SCHEDULED','PROCESSING')
            """, processId);
        long activeOffers = count("""
            SELECT count(*) FROM offers offer JOIN applications application
              ON application.application_id = offer.application_id
             WHERE application.process_id = :id AND offer.status = 'OFFERED'
            """, processId);
        if (activePublications > 0 || activeOffers > 0) {
            throw PrekinderDomainException.conflict("PROCESS_NOT_CLOSABLE",
                "Finaliza las publicaciones y ofertas pendientes antes de cerrar el proceso");
        }
        return transition(processId, expectedVersion, "PUBLISHED", "CLOSED", "PROCESS_CLOSED");
    }

    public ProcessSummary archive(UUID processId, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            Long activeBatches = jdbc.queryForObject("""
                SELECT count(*) FROM publication_batches
                 WHERE process_id = :processId AND status IN ('SCHEDULED','PROCESSING')
                """, Map.of("processId", processId), Long.class);
            if (activeBatches != null && activeBatches > 0) {
                throw PrekinderDomainException.conflict("PROCESS_HAS_ACTIVE_PUBLICATIONS",
                    "Cancela o finaliza los lotes de publicación antes de archivar");
            }
            int updated = jdbc.update("""
                UPDATE admission_processes SET status = 'ARCHIVED', version = version + 1, updated_at = now()
                 WHERE process_id = :processId AND version = :expectedVersion AND status = 'CLOSED'
                """, Map.of("processId", processId, "expectedVersion", expectedVersion));
            if (updated != 1) throw new VersionConflictException("El proceso debe estar cerrado para archivarlo");
            audit(actor.id(), "PROCESS_ARCHIVED", "PROCESS", processId);
            return process(processId);
        });
    }

    public ReadinessView readiness(UUID processId) {
        access.requireAdmin();
        ProcessSummary process = process(processId);
        List<ReadinessItem> items = new ArrayList<>();

        ProcessConfiguration config = loadConfiguration(processId);
        boolean configurationReady = config != null && config.incorporationFeeAmount() != null
            && config.ageReferenceDate() != null
            && config.requiredDocuments().contains("BIRTH_CERTIFICATE")
            && config.totalSeats() == config.maleSeats() + config.femaleSeats();
        items.add(item("OPEN_APPLICATIONS", "GENERAL_CONFIGURATION", "Configuración general", configurationReady,
            "Define aranceles, cupos, edad, documentos, agenda y plazos."));

        long publishedQuestionnaire = count("""
            SELECT count(*) FROM form_templates template
              JOIN form_template_versions version ON version.template_id = template.template_id
             WHERE template.process_id = :id AND template.code = 'COMPLEMENTARY_FORM'
               AND version.status = 'PUBLISHED'
            """, processId);
        items.add(item("OPEN_APPLICATIONS", "QUESTIONNAIRE", "Cuestionario de postulación",
            publishedQuestionnaire > 0, "Debe existir una versión revisada y publicada."));

        long configuredWaves = count("""
            SELECT count(*) FROM process_waves
             WHERE process_id = :id AND opens_at IS NOT NULL AND closes_at IS NOT NULL
            """, processId);
        items.add(item("OPEN_APPLICATIONS", "APPLICATION_WINDOWS", "Calendario de etapas", configuredWaves == 3,
            configuredWaves + " de 3 etapas configuradas."));

        long applicationCommunication = count("""
            SELECT count(*) FROM prekinder_communication_templates template
              JOIN prekinder_communication_template_versions version
                ON version.communication_template_id = template.communication_template_id
             WHERE template.process_id = :id AND template.event_code = 'APPLICATION_SUBMITTED'
               AND version.status = 'PUBLISHED'
            """, processId);
        items.add(item("OPEN_APPLICATIONS", "APPLICATION_COMMUNICATION", "Correo de postulación recibida",
            applicationCommunication > 0, "Debe existir una versión publicada."));

        Long assignedRubricsValue = jdbc.queryForObject("""
            SELECT count(DISTINCT assignment.instrument_code)
              FROM process_rubric_assignments assignment
              JOIN evaluation_template_versions version
                ON version.evaluation_template_version_id = assignment.evaluation_template_version_id
             WHERE assignment.process_id = :id AND assignment.instrument_code IN (:instruments)
               AND assignment.active AND version.status = 'PUBLISHED'
            """, new MapSqlParameterSource().addValue("id", processId)
            .addValue("instruments", REQUIRED_INSTRUMENTS), Long.class);
        long assignedRubrics = assignedRubricsValue == null ? 0 : assignedRubricsValue;
        items.add(item("OPEN_APPLICATIONS", "RUBRICS", "Pautas obligatorias",
            assignedRubrics >= REQUIRED_INSTRUMENTS.size(),
            assignedRubrics + " de " + REQUIRED_INSTRUMENTS.size() + " instrumentos asociados."));
        items.add(item("RUN_EVALUATIONS", "RUBRICS_OPERATION", "Pautas operativas",
            assignedRubrics >= REQUIRED_INSTRUMENTS.size(),
            assignedRubrics + " de " + REQUIRED_INSTRUMENTS.size() + " instrumentos asociados."));

        long rooms = count("SELECT count(*) FROM prekinder_rooms WHERE process_id = :id AND active", processId);
        items.add(item("OPEN_APPLICATIONS", "ROOMS", "Salas", rooms > 0,
            rooms == 0 ? "Crea al menos una sala." : rooms + " salas activas."));
        items.add(item("RUN_EVALUATIONS", "ROOMS_OPERATION", "Salas operativas", rooms > 0,
            rooms == 0 ? "Crea al menos una sala." : rooms + " salas activas."));

        long professionals = count("""
            SELECT count(DISTINCT actor_id) FROM prekinder_actor_role_assignments
             WHERE process_id = :id AND active
            """, processId);
        items.add(item("OPEN_APPLICATIONS", "TEAM", "Equipo", professionals > 0,
            professionals == 0 ? "Asigna al menos un profesional." : professionals + " profesionales asignados."));
        items.add(item("RUN_EVALUATIONS", "TEAM_OPERATION", "Equipo operativo", professionals > 0,
            professionals == 0 ? "Asigna al menos un profesional." : professionals + " profesionales asignados."));

        long familyInterviewers = count("""
            SELECT count(*) FROM prekinder_actor_role_assignments
             WHERE process_id = :id AND active AND role_code = 'PK_EVALUATOR_FAMILY_INTERVIEW'
            """, processId);
        items.add(item("OPEN_APPLICATIONS", "FAMILY_INTERVIEW_TEAM", "Entrevista familiar",
            familyInterviewers > 0, "Debe existir al menos un entrevistador familiar asignado."));
        items.add(item("RUN_EVALUATIONS", "FAMILY_INTERVIEW_OPERATION", "Entrevista familiar operativa",
            familyInterviewers > 0, "Debe existir al menos un entrevistador familiar asignado."));

        long resultCommunications = jdbc.queryForObject("""
            SELECT count(DISTINCT template.event_code)
              FROM prekinder_communication_templates template
              JOIN prekinder_communication_template_versions version
                ON version.communication_template_id = template.communication_template_id
             WHERE template.process_id = :id AND template.event_code IN (:events)
               AND version.status = 'PUBLISHED'
            """, new MapSqlParameterSource().addValue("id", processId).addValue("events", RESULT_EVENTS), Long.class);
        items.add(item("OPEN_APPLICATIONS", "RESULT_COMMUNICATIONS", "Comunicaciones de resultado",
            resultCommunications == RESULT_EVENTS.size(),
            resultCommunications + " de " + RESULT_EVENTS.size() + " plantillas publicadas."));
        items.add(item("PUBLISH_RESULTS", "RESULT_COMMUNICATIONS_PUBLISH", "Comunicaciones de resultado",
            resultCommunications == RESULT_EVENTS.size(),
            resultCommunications + " de " + RESULT_EVENTS.size() + " plantillas publicadas."));

        long applications = count("""
            SELECT count(*) FROM applications WHERE process_id = :id
             AND status NOT IN ('CANCELLED','INVALIDATED','DECLINED')
            """, processId);
        long decisions = count("""
            SELECT count(DISTINCT decision.application_id)
              FROM application_decisions_v2 decision
              JOIN applications application ON application.application_id = decision.application_id
             WHERE application.process_id = :id
               AND application.status NOT IN ('CANCELLED','INVALIDATED','DECLINED')
               AND decision.status IN ('DRAFT','SCHEDULED','PUBLISHED')
            """, processId);
        items.add(item("PUBLISH_RESULTS", "DECISIONS", "Decisiones completas",
            applications > 0 && decisions == applications,
            decisions + " de " + applications + " postulaciones con decisión."));

        Map<String, PhaseReadiness> phases = new LinkedHashMap<>();
        for (String phase : List.of("OPEN_APPLICATIONS", "RUN_EVALUATIONS", "PUBLISH_RESULTS")) {
            List<ReadinessItem> phaseItems = items.stream().filter(value -> value.phase().equals(phase)).toList();
            phases.put(phase, new PhaseReadiness(phaseItems.stream().allMatch(ReadinessItem::complete), phaseItems));
        }
        return new ReadinessView(process.processId(), process.status(), phases);
    }

    private ProcessSummary transition(UUID processId, long expectedVersion, String from, String to, String action) {
        PrekinderActor actor = access.requireAdmin();
        int updated = jdbc.update("""
            UPDATE admission_processes SET status = :toStatus, version = version + 1, updated_at = now()
             WHERE process_id = :processId AND version = :expectedVersion AND status = :fromStatus
            """, Map.of("processId", processId, "expectedVersion", expectedVersion,
                "fromStatus", from, "toStatus", to));
        if (updated != 1) throw new VersionConflictException("El proceso cambió o no permite esa transición");
        audit(actor.id(), action, "PROCESS", processId);
        return process(processId);
    }

    private ProcessSummary process(UUID processId) {
        return jdbc.queryForObject("""
            SELECT process_id, academic_year, name, status, starts_at, ends_at, version
              FROM admission_processes WHERE process_id = :id
            """, Map.of("id", processId), (rs, row) -> new ProcessSummary(
                rs.getObject("process_id", UUID.class), rs.getInt("academic_year"), rs.getString("name"),
                rs.getString("status"), timestamp(rs.getTimestamp("starts_at")),
                timestamp(rs.getTimestamp("ends_at")), rs.getLong("version")));
    }

    private ProcessConfiguration loadConfiguration(UUID processId) {
        return jdbc.queryForObject("""
            SELECT process_id, payment_enabled, payment_amount, payment_currency, payment_glosa,
                   payment_due_days, inclusion_enabled, inclusion_documents_required,
                   minimum_age_months, maximum_age_months, age_reference_date,
                   applicant_weight, family_weight, configuration_schema_version,
                   total_seats, male_seats, female_seats,
                   incorporation_fee_amount, incorporation_fee_currency, incorporation_fee_glosa,
                   required_documents, schedule_timezone, schedule_day_start, schedule_day_end,
                   schedule_block_minutes, schedule_max_blocks, suggested_parallel_capacity,
                   academic_group_size, psychomotor_group_size,
                   academic_required_evaluators, psychomotor_required_evaluators, initial_offer_hours,
                   waitlist_offer_hours, advisory_threshold, result_channel, version
              FROM prekinder_process_configuration WHERE process_id = :id
            """, Map.of("id", processId), (rs, row) -> new ProcessConfiguration(
                rs.getObject("process_id", UUID.class), rs.getBoolean("payment_enabled"),
                rs.getBigDecimal("payment_amount"), rs.getString("payment_currency"),
                rs.getString("payment_glosa"), rs.getInt("payment_due_days"),
                rs.getBoolean("inclusion_enabled"), rs.getBoolean("inclusion_documents_required"),
                rs.getInt("minimum_age_months"), rs.getInt("maximum_age_months"),
                rs.getObject("age_reference_date", LocalDate.class),
                rs.getBigDecimal("applicant_weight"), rs.getBigDecimal("family_weight"),
                rs.getInt("configuration_schema_version"), rs.getInt("total_seats"),
                rs.getInt("male_seats"), rs.getInt("female_seats"),
                rs.getBigDecimal("incorporation_fee_amount"), rs.getString("incorporation_fee_currency"),
                rs.getString("incorporation_fee_glosa"), readStringList(rs.getString("required_documents")),
                rs.getString("schedule_timezone"), rs.getObject("schedule_day_start", LocalTime.class),
                rs.getObject("schedule_day_end", LocalTime.class), rs.getInt("schedule_block_minutes"),
                rs.getInt("schedule_max_blocks"), rs.getInt("suggested_parallel_capacity"),
                rs.getInt("academic_group_size"), rs.getInt("psychomotor_group_size"),
                rs.getInt("academic_required_evaluators"), rs.getInt("psychomotor_required_evaluators"),
                rs.getInt("initial_offer_hours"), rs.getInt("waitlist_offer_hours"),
                rs.getBigDecimal("advisory_threshold"), rs.getString("result_channel"),
                rs.getLong("version")));
    }

    private long count(String sql, UUID processId) {
        Long value = jdbc.queryForObject(sql, Map.of("id", processId), Long.class);
        return value == null ? 0 : value;
    }

    private static ReadinessItem item(String phase, String code, String label, boolean complete, String detail) {
        return new ReadinessItem(phase, code, label, complete, !complete, detail);
    }

    private static void validate(ConfigurationCommand command) {
        if (command.paymentEnabled() && (command.paymentAmount() == null
            || command.paymentAmount().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new IllegalArgumentException("Define un monto mayor que cero cuando el pago está habilitado");
        }
        if (command.paymentCurrency() == null || !command.paymentCurrency().matches("[A-Za-z]{3}")) {
            throw new IllegalArgumentException("La moneda debe usar tres letras");
        }
        if (command.paymentGlosa() == null || command.paymentGlosa().isBlank()) {
            throw new IllegalArgumentException("La glosa de pago es obligatoria");
        }
        if (command.minimumAgeMonths() > command.maximumAgeMonths()) {
            throw new IllegalArgumentException("La edad mínima no puede superar la máxima");
        }
        if (command.totalSeats() <= 0 || command.maleSeats() < 0 || command.femaleSeats() < 0
            || command.maleSeats() + command.femaleSeats() != command.totalSeats())
            throw new IllegalArgumentException("Los cupos por sexo deben sumar el total de cupos");
        if (command.incorporationFeeAmount() != null && command.incorporationFeeAmount().signum() <= 0)
            throw new IllegalArgumentException("El arancel de incorporación debe ser mayor que cero");
        if (command.incorporationFeeCurrency() == null || !command.incorporationFeeCurrency().matches("[A-Za-z]{3}"))
            throw new IllegalArgumentException("La moneda de incorporación debe usar tres letras");
        if (command.incorporationFeeGlosa() == null || command.incorporationFeeGlosa().isBlank())
            throw new IllegalArgumentException("La glosa de incorporación es obligatoria");
        if (command.requiredDocuments() == null || command.requiredDocuments().isEmpty()
            || command.requiredDocuments().stream().anyMatch(value -> value == null || !value.matches("[A-Z0-9_]{2,64}")))
            throw new IllegalArgumentException("Define al menos un documento obligatorio válido");
        if (command.scheduleDayStart() == null || command.scheduleDayEnd() == null
            || !command.scheduleDayEnd().isAfter(command.scheduleDayStart()))
            throw new IllegalArgumentException("La jornada de evaluación es inválida");
        if (command.academicGroupSize() < 1 || command.psychomotorGroupSize() < 1
            || command.academicRequiredEvaluators() < 1 || command.psychomotorRequiredEvaluators() < 1)
            throw new IllegalArgumentException("Los tamaños de grupo y equipos evaluadores deben ser mayores que cero");
        if (command.advisoryThreshold() == null || command.advisoryThreshold().signum() < 0
            || command.advisoryThreshold().compareTo(new BigDecimal("100")) > 0)
            throw new IllegalArgumentException("El umbral orientativo debe estar entre 0 y 100");
    }

    private static String configurationJson(ProcessConfiguration configuration) {
        return writeJson(configuration);
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String writeJson(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("La configuración no tiene un formato válido", exception); }
    }

    private static List<String> readStringList(String value) {
        try { return JSON.readValue(value, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("Los documentos configurados no son válidos", exception); }
    }

    private static boolean criticalPolicyChanged(ProcessConfiguration current, ConfigurationCommand next) {
        return current.totalSeats() != next.totalSeats() || current.maleSeats() != next.maleSeats()
            || current.femaleSeats() != next.femaleSeats()
            || current.minimumAgeMonths() != next.minimumAgeMonths()
            || current.maximumAgeMonths() != next.maximumAgeMonths()
            || !java.util.Objects.equals(current.ageReferenceDate(), next.ageReferenceDate())
            || !java.util.Objects.equals(current.paymentAmount(), next.paymentAmount())
            || !java.util.Objects.equals(current.incorporationFeeAmount(), next.incorporationFeeAmount())
            || !current.requiredDocuments().equals(next.requiredDocuments());
    }

    private void synchronizeSeatLedger(UUID processId, int maleSeats, int femaleSeats) {
        for (Map.Entry<String, Integer> target : Map.of("MALE", maleSeats, "FEMALE", femaleSeats).entrySet()) {
            Long occupiedOutsideRange = jdbc.queryForObject("""
                SELECT count(*) FROM seat_ledger
                 WHERE process_id = :processId AND sex = :sex AND seat_number > :target
                   AND status <> 'AVAILABLE'
                """, Map.of("processId", processId, "sex", target.getKey(), "target", target.getValue()), Long.class);
            if (occupiedOutsideRange != null && occupiedOutsideRange > 0)
                throw PrekinderDomainException.conflict("SEAT_QUOTA_IN_USE",
                    "No se puede reducir el cupo porque existen asientos reservados o matriculados");
            jdbc.update("""
                DELETE FROM seat_ledger
                 WHERE process_id = :processId AND sex = :sex AND seat_number > :target
                   AND status = 'AVAILABLE'
                """, Map.of("processId", processId, "sex", target.getKey(), "target", target.getValue()));
            jdbc.update("""
                INSERT INTO seat_ledger(seat_id, process_id, sex, seat_number)
                SELECT gen_random_uuid(), :processId, :sex, number
                  FROM generate_series(1, :target) number
                ON CONFLICT (process_id, sex, seat_number) DO NOTHING
                """, Map.of("processId", processId, "sex", target.getKey(), "target", target.getValue()));
        }
    }

    private void audit(UUID actorId, String action, String type, UUID aggregateId) {
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result)
            VALUES (:id, :actorId, :action, :type, :aggregateId, 'SUCCESS')
            """, Map.of("id", UUID.randomUUID(), "actorId", actorId, "action", action,
                "type", type, "aggregateId", aggregateId));
    }

    private static Instant timestamp(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record ProcessCommand(int academicYear, String name, long expectedVersion) {}
    public record ConfigurationCommand(boolean paymentEnabled, BigDecimal paymentAmount, String paymentCurrency,
        String paymentGlosa, int paymentDueDays, boolean inclusionEnabled,
        boolean inclusionDocumentsRequired, int minimumAgeMonths, int maximumAgeMonths,
        LocalDate ageReferenceDate, int totalSeats, int maleSeats, int femaleSeats,
        BigDecimal incorporationFeeAmount, String incorporationFeeCurrency, String incorporationFeeGlosa,
        List<String> requiredDocuments, String scheduleTimezone, LocalTime scheduleDayStart,
        LocalTime scheduleDayEnd, int scheduleBlockMinutes, int scheduleMaxBlocks,
        int suggestedParallelCapacity, int academicGroupSize, int psychomotorGroupSize,
        int academicRequiredEvaluators, int psychomotorRequiredEvaluators,
        int initialOfferHours, int waitlistOfferHours, BigDecimal advisoryThreshold,
        long expectedVersion) {}
    public record ProcessConfiguration(UUID processId, boolean paymentEnabled, BigDecimal paymentAmount,
        String paymentCurrency, String paymentGlosa, int paymentDueDays, boolean inclusionEnabled,
        boolean inclusionDocumentsRequired, int minimumAgeMonths, int maximumAgeMonths,
        LocalDate ageReferenceDate, BigDecimal applicantWeight, BigDecimal familyWeight,
        int schemaVersion, int totalSeats, int maleSeats, int femaleSeats,
        BigDecimal incorporationFeeAmount, String incorporationFeeCurrency, String incorporationFeeGlosa,
        List<String> requiredDocuments, String scheduleTimezone, LocalTime scheduleDayStart,
        LocalTime scheduleDayEnd, int scheduleBlockMinutes, int scheduleMaxBlocks,
        int suggestedParallelCapacity, int academicGroupSize, int psychomotorGroupSize,
        int academicRequiredEvaluators, int psychomotorRequiredEvaluators,
        int initialOfferHours, int waitlistOfferHours, BigDecimal advisoryThreshold,
        String resultChannel, long version) {}
    public record ProcessSummary(UUID processId, int academicYear, String name, String status,
        Instant startsAt, Instant endsAt, long version) {}
    public record ReadinessItem(String phase, String code, String label, boolean complete,
        boolean blocking, String detail) {}
    public record PhaseReadiness(boolean ready, List<ReadinessItem> items) {}
    public record ReadinessView(UUID processId, String processStatus, Map<String, PhaseReadiness> phases) {}
}
