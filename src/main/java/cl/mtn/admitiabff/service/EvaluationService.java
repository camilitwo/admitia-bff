package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.evaluation.EvaluationEntity;
import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import cl.mtn.admitiabff.domain.person.GuardianEntity;
import cl.mtn.admitiabff.domain.person.ParentEntity;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.util.CsvUtils;
import cl.mtn.admitiabff.util.EmailDisplayFormatter;
import cl.mtn.admitiabff.util.JsonSupport;
import cl.mtn.admitiabff.util.TemplateUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EvaluationService {
    private static final Map<String, String> STRUCTURED_FIELD_ALIASES = Map.ofEntries(
        Map.entry("academicReadiness", "academicReadiness"),
        Map.entry("academic_readiness", "academicReadiness"),
        Map.entry("behavioralAssessment", "behavioralAssessment"),
        Map.entry("behavioral_assessment", "behavioralAssessment"),
        Map.entry("emotionalMaturity", "emotionalMaturity"),
        Map.entry("emotional_maturity", "emotionalMaturity"),
        Map.entry("socialSkillsAssessment", "socialSkillsAssessment"),
        Map.entry("social_skills_assessment", "socialSkillsAssessment"),
        Map.entry("socialSkills", "socialSkillsAssessment"),
        Map.entry("motivationAssessment", "motivationAssessment"),
        Map.entry("motivation_assessment", "motivationAssessment"),
        Map.entry("familySupportAssessment", "familySupportAssessment"),
        Map.entry("family_support_assessment", "familySupportAssessment"),
        Map.entry("familySupport", "familySupportAssessment"),
        Map.entry("integrationPotential", "integrationPotential"),
        Map.entry("integration_potential", "integrationPotential"),
        Map.entry("finalRecommendation", "finalRecommendation"),
        Map.entry("final_recommendation", "finalRecommendation")
    );
    private final EvaluationRepository evaluationRepository;
    private final ApplicationRepository applicationRepository;
    private final InterviewRepository interviewRepository;
    private final UserRepository userRepository;
    private final cl.mtn.admitiabff.service.notification.EmailComposerService emailComposerService;
    private final AuthService authService;
    private final JsonSupport jsonSupport;

    public EvaluationService(EvaluationRepository evaluationRepository, ApplicationRepository applicationRepository, InterviewRepository interviewRepository, UserRepository userRepository, cl.mtn.admitiabff.service.notification.EmailComposerService emailComposerService, AuthService authService, JsonSupport jsonSupport) {
        this.evaluationRepository = evaluationRepository;
        this.applicationRepository = applicationRepository;
        this.interviewRepository = interviewRepository;
        this.userRepository = userRepository;
        this.emailComposerService = emailComposerService;
        this.authService = authService;
        this.jsonSupport = jsonSupport;
    }

    public Map<String, Object> all() {
        List<Map<String, Object>> data = evaluationRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
        return Map.of("success", true, "data", data, "content", data, "count", data.size());
    }

    public Map<String, Object> statistics() {
        Map<String, Object> byStatus = evaluationRepository.countByStatus().stream().collect(Collectors.toMap(EvaluationRepository.KeyCountView::getKey, EvaluationRepository.KeyCountView::getTotal, (a, b) -> b, LinkedHashMap::new));
        Map<String, Object> byType = evaluationRepository.countByType().stream().collect(Collectors.toMap(EvaluationRepository.KeyCountView::getKey, EvaluationRepository.KeyCountView::getTotal, (a, b) -> b, LinkedHashMap::new));
        return Map.of("success", true, "data", Map.of("total", evaluationRepository.count(), "byStatus", byStatus, "byType", byType, "averageScore", evaluationRepository.averageScore()));
    }

    public Map<String, Object> assignments() {
        List<Map<String, Object>> data = evaluationRepository.findAssignments(List.of(EvaluationStatus.PENDING, EvaluationStatus.IN_PROGRESS)).stream().map(evaluation -> {
            Map<String, Object> response = new LinkedHashMap<>(toResponse(evaluation));
            if (evaluation.getEvaluator() != null) {
                response.put("evaluator", Map.of("id", evaluation.getEvaluator().getId(), "name", evaluation.getEvaluator().getFirstName() + " " + evaluation.getEvaluator().getLastName(), "email", evaluation.getEvaluator().getEmail()));
            }
            return response;
        }).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    public ResponseEntity<?> export(String status, String type, String format) {
        List<Map<String, Object>> data = evaluationRepository.findAllByOrderByCreatedAtDesc().stream()
            .filter(item -> status == null || status.isBlank() || item.getStatus().name().equalsIgnoreCase(status))
            .filter(item -> type == null || type.isBlank() || item.getEvaluationType().equalsIgnoreCase(type))
            .map(this::toResponse)
            .toList();
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(CsvUtils.toCsv(data));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    public Map<String, Object> byApplication(Long applicationId) { return wrap(evaluationRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId)); }
    public Map<String, Object> byEvaluator(Long evaluatorId) { return wrap(evaluationRepository.findByEvaluatorIdOrderByCreatedAtDesc(evaluatorId)); }
    public Map<String, Object> evaluatorPending(Long evaluatorId) { return wrap(evaluationRepository.findByEvaluatorIdAndStatusInOrderByCreatedAtDesc(evaluatorId, List.of(EvaluationStatus.PENDING, EvaluationStatus.IN_PROGRESS))); }
    public Map<String, Object> evaluatorCompleted(Long evaluatorId) { return wrap(evaluationRepository.findByEvaluatorIdAndStatusOrderByCreatedAtDesc(evaluatorId, EvaluationStatus.COMPLETED)); }
    public Map<String, Object> byType(String type) { return wrap(evaluationRepository.findByEvaluationTypeOrderByCreatedAtDesc(type)); }
    public Map<String, Object> bySubject(String subject) { return wrap(evaluationRepository.findBySubjectOrderByCreatedAtDesc(subject)); }
    public Map<String, Object> myEvaluations() {
        AuthService.AuthContextHolder auth = authService.requireAuth();
        Long userId = auth.id();
        List<EvaluationEntity> assignedEvaluations = evaluationRepository.findByEvaluatorIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> evaluations = new java.util.ArrayList<>(
            assignedEvaluations.stream().map(this::toResponse).toList()
        );
        java.util.Set<String> addedKeys = new java.util.LinkedHashSet<>();
        assignedEvaluations.forEach(evaluation -> {
            String key = evaluationKey(
                evaluation.getApplication() == null ? null : evaluation.getApplication().getId(),
                evaluation.getEvaluationType()
            );
            if (key != null) addedKeys.add(key);
        });
        List<InterviewStatus> excluded = List.of(
            InterviewStatus.CANCELLED,
            InterviewStatus.RESCHEDULED,
            InterviewStatus.REJECTED_BY_FAMILY
        );
        interviewRepository.findVisibleForInterviewer(userId, excluded).stream()
            .forEach(interview -> addLinkedInterviewEvaluations(evaluations, addedKeys, interview, userId));
        return Map.of("success", true, "data", evaluations, "count", evaluations.size());
    }

    private void addLinkedInterviewEvaluations(
        List<Map<String, Object>> evaluations,
        java.util.Set<String> addedKeys,
        cl.mtn.admitiabff.domain.interview.InterviewEntity interview,
        Long userId
    ) {
        Long applicationId = interview.getApplication() == null ? null : interview.getApplication().getId();
        if (applicationId == null) return;

        for (String evaluationType : evaluationTypesForUser(interview, userId)) {
            String key = evaluationKey(applicationId, evaluationType);
            if (key == null || addedKeys.contains(key)) continue;

            evaluationRepository.findByApplicationIdAndEvaluationType(applicationId, evaluationType)
                .ifPresent(evaluation -> {
                    Map<String, Object> response = new LinkedHashMap<>(toResponse(evaluation));
                    applyInterviewMetadata(response, interview, evaluationType);
                    evaluations.add(response);
                    addedKeys.add(key);
                });
        }
    }

    private void applyInterviewMetadata(
        Map<String, Object> response,
        cl.mtn.admitiabff.domain.interview.InterviewEntity interview,
        String evaluationType
    ) {
        response.put("interviewId", interview.getId());
        response.put("interviewType", interview.getInterviewType());
        response.put("evaluationType", evaluationType);
        response.put("type", evaluationType);
        response.put("scheduledDate", interview.getScheduledDate());
        if (response.get("evaluationDate") == null && interview.getScheduledDate() != null && interview.getScheduledTime() != null) {
            response.put("evaluationDate", interview.getScheduledDate().atTime(interview.getScheduledTime()));
        }
        if (response.get("observations") == null) {
            response.put("observations", interview.getNotes());
        }
        UserEntity responsibleEvaluator = expectedEvaluator(interview, evaluationType);
        if (response.get("evaluatorId") == null && responsibleEvaluator != null) {
            response.put("evaluatorId", responsibleEvaluator.getId());
            response.put("evaluator", evaluatorMap(responsibleEvaluator));
        }
        if (interview.getApplication() != null && interview.getApplication().getStudent() != null) {
            var student = interview.getApplication().getStudent();
            response.put("studentName", student.getFirstName() + " " + student.getPaternalLastName() + " " + student.getMaternalLastName());
            response.put("gradeApplied", student.getGradeApplied());
        }
    }
    public Map<String, Object> familyInterviewTemplate(String grade) { return Map.of("success", true, "data", Map.of("grade", grade, "sections", List.of("Historia familiar", "Motivación", "Rutinas", "Observaciones"))); }
    public Map<String, Object> get(Long id) { return toResponse(loadAccessible(id)); }
    public Map<String, Object> familyInterviewData(Long evaluationId) { EvaluationEntity entity = loadAccessible(evaluationId); return Map.of("success", true, "data", jsonSupport.readMap(entity.getInterviewData()), "score", entity.getFamilyInterviewScore() == null ? BigDecimal.ZERO : entity.getFamilyInterviewScore()); }

    /**
     * Repara de forma idempotente las evaluaciones que normalmente se crean al agendar una
     * entrevista. Este endpoint cubre entrevistas históricas insertadas directamente por SQL.
     * No envía correos ni notificaciones.
     */
    @Transactional
    public Map<String, Object> ensureInterviewEvaluations(Long interviewId) {
        AuthService.AuthContextHolder auth = authService.requireAuth();
        var interview = interviewRepository.findById(interviewId)
            .orElseThrow(() -> new IllegalArgumentException("Entrevista no encontrada"));

        boolean assigned = (interview.getInterviewer() != null && interview.getInterviewer().getId().equals(auth.id()))
            || (interview.getSecondInterviewer() != null && interview.getSecondInterviewer().getId().equals(auth.id()));
        if (!assigned && !authService.isAdminContext(auth)) {
            throw new AccessDeniedException("La entrevista no está asignada al usuario autenticado");
        }
        if (interview.getApplication() == null) {
            throw new IllegalArgumentException("La entrevista no tiene una postulación asociada");
        }
        if (!isActiveInterview(interview)) {
            throw new IllegalArgumentException("No se pueden generar evaluaciones para una entrevista inactiva");
        }

        List<String> accessibleTypes = authService.isAdminContext(auth)
            ? mapInterviewTypesToEvaluationTypes(interview.getInterviewType())
            : evaluationTypesForUser(interview, auth.id());
        List<Map<String, Object>> data = accessibleTypes.stream()
            .map(evaluationType -> {
                UserEntity expectedEvaluator = expectedEvaluator(interview, evaluationType);
                EvaluationEntity evaluation = evaluationRepository
                    .findByApplicationIdAndEvaluationType(interview.getApplication().getId(), evaluationType)
                    .orElseGet(() -> {
                        EvaluationEntity created = new EvaluationEntity();
                        created.setApplication(interview.getApplication());
                        created.setEvaluationType(evaluationType);
                        created.setStatus(EvaluationStatus.PENDING);
                        created.setEvaluator(expectedEvaluator);
                        if (interview.getScheduledDate() != null && interview.getScheduledTime() != null) {
                            created.setEvaluationDate(interview.getScheduledDate().atTime(interview.getScheduledTime()));
                        }
                        return evaluationRepository.save(created);
                    });
                if (evaluation.getStatus() != EvaluationStatus.COMPLETED
                    && expectedEvaluator != null && (evaluation.getEvaluator() == null
                    || !expectedEvaluator.getId().equals(evaluation.getEvaluator().getId()))) {
                    evaluation.setEvaluator(expectedEvaluator);
                    evaluationRepository.save(evaluation);
                }
                Map<String, Object> response = new LinkedHashMap<>(toResponse(evaluation));
                applyInterviewMetadata(response, interview, evaluationType);
                return response;
            })
            .toList();

        if (data.isEmpty()) {
            throw new IllegalArgumentException("El tipo de entrevista no genera una evaluación realizable");
        }
        return Map.of("success", true, "data", data, "count", data.size());
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        EvaluationEntity entity = new EvaluationEntity();
        merge(entity, payload);
        syncCompletionTimestamp(entity);
        return Map.of("success", true, "message", "Evaluación creada correctamente", "data", toResponse(evaluationRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> payload) {
        AuthService.AuthContextHolder auth = authService.requireAuth();
        EvaluationEntity entity = loadAccessible(id, auth);
        validateProfessionalPayload(auth, payload);
        merge(entity, payload);
        syncCompletionTimestamp(entity);
        return Map.of("success", true, "message", "Evaluación actualizada", "data", toResponse(evaluationRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        evaluationRepository.deleteById(id);
        return Map.of("success", true, "message", "Evaluación eliminada correctamente");
    }

    @Transactional
    public Map<String, Object> complete(Long id, Map<String, Object> payload) {
        AuthService.AuthContextHolder auth = authService.requireAuth();
        EvaluationEntity entity = loadAccessible(id, auth);
        validateProfessionalPayload(auth, payload);
        merge(entity, payload);
        entity.setStatus(EvaluationStatus.COMPLETED);
        syncCompletionTimestamp(entity);
        return Map.of("success", true, "message", "Evaluación completada", "data", toResponse(evaluationRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> assign(Long id, Map<String, Object> payload) {
        EvaluationEntity entity = load(id);
        var evaluator = userRepository.findById(((Number) payload.get("evaluatorId")).longValue())
            .orElseThrow(() -> new IllegalArgumentException("Evaluador no encontrado"));

        validateEvaluatorSubject(entity, evaluator);

        entity.setEvaluator(evaluator);
        entity.setEvaluationDate(parseDateTime(payload.get("evaluationDate")));
        if (entity.getStatus() != EvaluationStatus.COMPLETED) {
            entity.setStatus(EvaluationStatus.IN_PROGRESS);
        }
        EvaluationEntity saved = evaluationRepository.save(entity);

        try {
            UserEntity ev = saved.getEvaluator();
            String to = ev != null ? ev.getEmail() : null;
            if (to != null && !to.isBlank()) {
                String studentName = saved.getApplication() != null && saved.getApplication().getStudent() != null
                        ? (saved.getApplication().getStudent().getFirstName() + " "
                                + saved.getApplication().getStudent().getPaternalLastName()).trim()
                        : "";
                String gradeApplied = saved.getApplication() != null && saved.getApplication().getStudent() != null
                        ? EmailDisplayFormatter.grade(saved.getApplication().getStudent().getGradeApplied())
                        : "";
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("evaluatorName", ev.getFirstName() == null ? "" : ev.getFirstName());
                data.put("studentName", studentName);
                data.put("gradeApplied", gradeApplied);
                data.put("deadline", saved.getEvaluationDate() == null ? "" : String.valueOf(saved.getEvaluationDate()));
                data.put("evaluationId", saved.getId());

                emailComposerService.send(EmailRequestDTO.builder()
                        .template(TemplateUtils.generateTemplate(EmailTemplate.EVALUATION_ASSIGNMENT.name(), data))
                        .to(to)
                        .subject(EmailTemplate.EVALUATION_ASSIGNMENT.getDefaultSubject())
                        .recipientType("USER")
                        .recipientId(ev.getId())
                        .data(data)
                        .build());
            }
        } catch (Exception ignored) {
            // Best-effort: el envío no debe romper la asignación.
        }
        return Map.of("success", true, "message", "Evaluador asignado", "data", toResponse(saved));
    }

    @Transactional
    public Map<String, Object> reschedule(Long id, Map<String, Object> payload) {
        EvaluationEntity entity = load(id);
        entity.setEvaluationDate(parseDateTime(payload.getOrDefault("evaluationDate", payload.get("scheduledDate"))));
        entity.setStatus(EvaluationStatus.IN_PROGRESS);
        return Map.of("success", true, "message", "Evaluación reprogramada", "data", toResponse(evaluationRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> cancel(Long id, Map<String, Object> payload) {
        EvaluationEntity entity = load(id);
        entity.setStatus(EvaluationStatus.CANCELLED);
        entity.setCancellationReason(stringValue(payload.get("reason")));
        return Map.of("success", true, "message", "Evaluación cancelada", "data", toResponse(evaluationRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> saveFamilyInterviewData(Long evaluationId, Map<String, Object> payload) {
        EvaluationEntity entity = loadAccessible(evaluationId);
        Map<String, Object> interviewData = payload.get("interviewData") instanceof Map<?, ?> map ? (Map<String, Object>) map : payload;
        BigDecimal score = calculateInterviewScore(interviewData);
        entity.setInterviewData(jsonSupport.write(interviewData));
        entity.setFamilyInterviewScore(score);
        entity.setStatus(EvaluationStatus.COMPLETED);
        entity.setCompletedAt(LocalDateTime.now());
        evaluationRepository.save(entity);
        return Map.of("success", true, "message", "Entrevista familiar guardada", "data", interviewData, "score", score);
    }

    @Transactional
    public Map<String, Object> bulkAssign(Map<String, Object> payload) {
        List<?> ids = (List<?>) payload.getOrDefault("evaluationIds", List.of());
        for (Object id : ids) {
            assign(((Number) id).longValue(), payload);
        }
        return Map.of("success", true, "message", "Asignación masiva completada", "data", ids.stream().map(id -> toResponse(load(((Number) id).longValue()))).toList());
    }

    @Transactional
    public Map<String, Object> migrateInterviews() {
        long created = 0;
        for (var interview : applicationRepository.findAll()) {
            boolean hasFamilyInterviewEvaluation = !evaluationRepository.findFamilyInterviewByApplicationId(interview.getId()).isEmpty();
            if (!hasFamilyInterviewEvaluation && !interviewRepository.findByApplicationIdOrderByScheduledDateDesc(interview.getId()).isEmpty()) {
                EvaluationEntity entity = new EvaluationEntity();
                entity.setApplication(interview);
                entity.setEvaluationType("FAMILY_INTERVIEW");
                entity.setStatus(EvaluationStatus.PENDING);
                evaluationRepository.save(entity);
                created++;
            }
        }
        return Map.of("success", true, "message", "Migración completada", "data", Map.of("created", created));
    }

    private void validateEvaluatorSubject(EvaluationEntity evaluation, UserEntity evaluator) {
        String evaluationSubject = evaluation.getSubject();
        String evaluatorSubject = evaluator.getSubject();

        if (evaluationSubject != null && !evaluationSubject.isBlank() &&
            !evaluationSubject.equals(evaluatorSubject)) {
            throw new IllegalArgumentException(
                String.format("El evaluador no tiene la asignatura requerida. Asignatura de la evaluación: %s, Asignatura del evaluador: %s",
                    evaluationSubject, evaluatorSubject)
            );
        }
    }

    private void merge(EvaluationEntity entity, Map<String, Object> payload) {
        if (payload.get("applicationId") instanceof Number number) {
            entity.setApplication(applicationRepository.findActiveById(number.longValue()).orElseThrow(() -> new IllegalArgumentException("Postulación no encontrada")));
        }
        if (payload.get("evaluatorId") instanceof Number number) {
            entity.setEvaluator(userRepository.findById(number.longValue()).orElseThrow(() -> new IllegalArgumentException("Evaluador no encontrado")));
        }
        entity.setEvaluationType(stringValue(payload.getOrDefault("type", payload.getOrDefault("evaluationType", entity.getEvaluationType()))));
        entity.setSubject(stringValue(payload.getOrDefault("subject", entity.getSubject())));
        entity.setEducationalLevel(stringValue(payload.getOrDefault("educationalLevel", entity.getEducationalLevel())));
        if (payload.get("status") != null) entity.setStatus(EvaluationStatus.valueOf(String.valueOf(payload.get("status")).toUpperCase()));
        entity.setEvaluationDate(parseDateTime(payload.getOrDefault("evaluationDate", entity.getEvaluationDate())));
        entity.setScore(decimalValue(payload.getOrDefault("score", entity.getScore())));
        entity.setMaxScore(decimalValue(payload.getOrDefault("maxScore", entity.getMaxScore())));
        entity.setGrade(stringValue(payload.getOrDefault("grade", entity.getGrade())));
        entity.setStrengths(stringValue(payload.getOrDefault("strengths", entity.getStrengths())));
        entity.setRecommendations(stringValue(payload.getOrDefault("recommendations", entity.getRecommendations())));
        entity.setObservations(stringValue(payload.getOrDefault("observations", payload.getOrDefault("comments", entity.getObservations()))));
        entity.setAreasForImprovement(stringValue(payload.getOrDefault("areasForImprovement", payload.getOrDefault("areas_for_improvement", entity.getAreasForImprovement()))));
        persistInterviewData(entity, payload);

        if (entity.getEvaluator() != null) {
            validateEvaluatorSubject(entity, entity.getEvaluator());
        }
    }

    private Map<String, Object> wrap(List<EvaluationEntity> entities) {
        List<Map<String, Object>> data = entities.stream().map(this::toResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    private EvaluationEntity load(Long id) {
        return evaluationRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Evaluación no encontrada"));
    }

    private EvaluationEntity loadAccessible(Long id) {
        return loadAccessible(id, authService.requireAuth());
    }

    private EvaluationEntity loadAccessible(Long id, AuthService.AuthContextHolder auth) {
        EvaluationEntity entity = load(id);
        if (authService.isAdminContext(auth)) return entity;
        if (entity.getEvaluator() != null && entity.getEvaluator().getId().equals(auth.id())) return entity;

        Long applicationId = entity.getApplication() == null ? null : entity.getApplication().getId();
        boolean linkedToUser = applicationId != null
            && interviewRepository.findByApplicationIdOrderByScheduledDateDesc(applicationId).stream()
                .filter(this::isActiveInterview)
                .filter(interview -> mapInterviewTypesToEvaluationTypes(interview.getInterviewType())
                    .contains(entity.getEvaluationType()))
                .findFirst()
                .map(interview -> evaluationTypesForUser(interview, auth.id()).contains(entity.getEvaluationType()))
                .orElse(false);
        if (!linkedToUser) {
            throw new AccessDeniedException("La evaluación no está asignada al usuario autenticado");
        }
        return entity;
    }

    private void validateProfessionalPayload(AuthService.AuthContextHolder auth, Map<String, Object> payload) {
        if (authService.isAdminContext(auth)) return;
        List<String> protectedFields = List.of(
            "applicationId", "evaluatorId", "type", "evaluationType",
            "subject", "educationalLevel", "evaluationDate"
        );
        if (protectedFields.stream().anyMatch(payload::containsKey)) {
            throw new AccessDeniedException("La asignación y el tipo de evaluación sólo pueden ser modificados por administración");
        }
        if (payload.containsKey("status")) {
            EvaluationStatus requested = EvaluationStatus.valueOf(String.valueOf(payload.get("status")).toUpperCase());
            if (requested != EvaluationStatus.IN_PROGRESS && requested != EvaluationStatus.COMPLETED) {
                throw new AccessDeniedException("El profesional sólo puede guardar un borrador o completar su evaluación");
            }
        }
    }

    private Map<String, Object> toResponse(EvaluationEntity entity) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entity.getId());
        response.put("applicationId", entity.getApplication() == null ? null : entity.getApplication().getId());
        response.put("evaluatorId", entity.getEvaluator() == null ? null : entity.getEvaluator().getId());
        response.put("type", entity.getEvaluationType());
        response.put("subject", entity.getSubject());
        response.put("educationalLevel", entity.getEducationalLevel());
        response.put("status", entity.getStatus().name());
        response.put("evaluationDate", entity.getEvaluationDate());
        response.put("score", entity.getScore());
        response.put("maxScore", entity.getMaxScore());
        response.put("grade", entity.getGrade());
        response.put("strengths", entity.getStrengths());
        response.put("recommendations", entity.getRecommendations());
        response.put("observations", entity.getObservations());
        response.put("areasForImprovement", entity.getAreasForImprovement());
        Map<String, Object> structuredData = jsonSupport.readMap(entity.getInterviewData());
        response.put("interviewData", structuredData);
        structuredData.forEach(response::putIfAbsent);
        response.put("createdAt", entity.getCreatedAt());
        response.put("updatedAt", entity.getUpdatedAt());
        response.put("completedAt", entity.getCompletedAt());
        if (entity.getApplication() != null) {
            response.put("application", applicationMap(entity.getApplication()));
        }
        if (entity.getEvaluator() != null) {
            response.put("evaluator", evaluatorMap(entity.getEvaluator()));
        }
        return response;
    }

    private Map<String, Object> applicationMap(ApplicationEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        if (entity.getStudent() != null) {
            StudentEntity student = entity.getStudent();
            Map<String, Object> studentMap = new LinkedHashMap<>();
            studentMap.put("id", student.getId());
            studentMap.put("firstName", student.getFirstName());
            studentMap.put("paternalLastName", student.getPaternalLastName());
            studentMap.put("maternalLastName", student.getMaternalLastName());
            studentMap.put("lastName", (value(student.getPaternalLastName()) + " " + value(student.getMaternalLastName())).trim());
            studentMap.put("rut", student.getRut());
            studentMap.put("birthDate", student.getBirthDate());
            studentMap.put("gradeApplied", student.getGradeApplied());
            studentMap.put("grade", student.getGradeApplied());
            studentMap.put("currentSchool", student.getCurrentSchool());
            studentMap.put("email", student.getEmail());
            studentMap.put("address", student.getAddress());
            studentMap.put("additionalNotes", student.getAdditionalNotes());
            studentMap.put("gender", student.getGender());
            map.put("student", studentMap);
        }
        if (entity.getFather() != null) {
            map.put("father", parentMap(entity.getFather()));
        }
        if (entity.getMother() != null) {
            map.put("mother", parentMap(entity.getMother()));
        }
        if (entity.getGuardian() != null) {
            GuardianEntity guardian = entity.getGuardian();
            map.put("guardian", Map.of("id", guardian.getId(), "fullName", guardian.getFullName(), "rut", guardian.getRut(), "email", guardian.getEmail(), "phone", guardian.getPhone(), "relationship", guardian.getRelationship()));
        }
        return map;
    }

    private Map<String, Object> parentMap(ParentEntity entity) {
        if (entity == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("fullName", entity.getFullName());
        map.put("rut", entity.getRut());
        map.put("email", entity.getEmail());
        map.put("phone", entity.getPhone());
        map.put("address", entity.getAddress());
        map.put("profession", entity.getProfession());
        return map;
    }

    private Map<String, Object> evaluatorMap(UserEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("firstName", entity.getFirstName());
        map.put("lastName", entity.getLastName());
        map.put("email", entity.getEmail());
        map.put("subject", entity.getSubject());
        return map;
    }

    private String value(String value) { return value == null ? "" : value; }

    private BigDecimal calculateInterviewScore(Map<String, Object> interviewData) {
        return sumInterviewScores(interviewData, false);
    }

    private BigDecimal sumInterviewScores(Object value, boolean scoreField) {
        if (scoreField && value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                .map(entry -> sumInterviewScores(entry.getValue(), "score".equals(String.valueOf(entry.getKey()))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal decimalValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof BigDecimal bigDecimal) return bigDecimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        return new BigDecimal(String.valueOf(value));
    }

    private LocalDateTime parseDateTime(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof LocalDateTime dateTime) return dateTime;
        String str = String.valueOf(value).trim();
        // Handle date-only format (e.g., "2026-05-01") by appending start of day
        if (str.length() == 10) {
            return java.time.LocalDate.parse(str).atStartOfDay();
        }
        return LocalDateTime.parse(str);
    }

    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }

    @SuppressWarnings("unchecked")
    private void persistInterviewData(EvaluationEntity entity, Map<String, Object> payload) {
        if (payload.get("interviewData") instanceof Map<?, ?> interviewData) {
            entity.setInterviewData(jsonSupport.write((Map<String, Object>) interviewData));
            return;
        }

        Map<String, Object> merged = new LinkedHashMap<>(jsonSupport.readMap(entity.getInterviewData()));
        boolean changed = false;
        for (Map.Entry<String, String> alias : STRUCTURED_FIELD_ALIASES.entrySet()) {
            if (payload.containsKey(alias.getKey())) {
                merged.put(alias.getValue(), payload.get(alias.getKey()));
                changed = true;
            }
        }
        if (changed) {
            entity.setInterviewData(jsonSupport.write(merged));
        }
    }

    private void syncCompletionTimestamp(EvaluationEntity entity) {
        if (entity.getStatus() == EvaluationStatus.COMPLETED && entity.getCompletedAt() == null) {
            entity.setCompletedAt(LocalDateTime.now());
        }
    }

    private String evaluationKey(Long applicationId, String evaluationType) {
        if (applicationId == null || evaluationType == null || evaluationType.isBlank()) return null;
        return applicationId + ":" + evaluationType;
    }

    private List<String> mapInterviewTypesToEvaluationTypes(String interviewType) {
        if (interviewType == null) return List.of();
        return switch (interviewType) {
            case "FAMILY" -> List.of("FAMILY_INTERVIEW");
            case "CYCLE_DIRECTOR" -> List.of("CYCLE_DIRECTOR_INTERVIEW", "CYCLE_DIRECTOR_REPORT", "PSYCHOLOGICAL_INTERVIEW");
            case "PSYCHOLOGICAL" -> List.of("PSYCHOLOGICAL_INTERVIEW");
            default -> List.of(interviewType);
        };
    }

    private List<String> evaluationTypesForUser(
        cl.mtn.admitiabff.domain.interview.InterviewEntity interview,
        Long userId
    ) {
        if (userId == null || interview.getInterviewType() == null) return List.of();
        boolean primary = interview.getInterviewer() != null && userId.equals(interview.getInterviewer().getId());
        boolean secondary = interview.getSecondInterviewer() != null && userId.equals(interview.getSecondInterviewer().getId());
        return switch (interview.getInterviewType()) {
            case "FAMILY" -> primary || secondary ? List.of("FAMILY_INTERVIEW") : List.of();
            case "CYCLE_DIRECTOR" -> primary
                ? List.of("CYCLE_DIRECTOR_INTERVIEW", "CYCLE_DIRECTOR_REPORT")
                : secondary ? List.of("PSYCHOLOGICAL_INTERVIEW") : List.of();
            case "PSYCHOLOGICAL" -> primary || secondary ? List.of("PSYCHOLOGICAL_INTERVIEW") : List.of();
            default -> primary || secondary ? List.of(interview.getInterviewType()) : List.of();
        };
    }

    private boolean isActiveInterview(cl.mtn.admitiabff.domain.interview.InterviewEntity interview) {
        return interview.getStatus() != InterviewStatus.CANCELLED
            && interview.getStatus() != InterviewStatus.RESCHEDULED
            && interview.getStatus() != InterviewStatus.REJECTED_BY_FAMILY;
    }

    private UserEntity expectedEvaluator(
        cl.mtn.admitiabff.domain.interview.InterviewEntity interview,
        String evaluationType
    ) {
        if ("CYCLE_DIRECTOR".equals(interview.getInterviewType())
            && "PSYCHOLOGICAL_INTERVIEW".equals(evaluationType)) {
            return interview.getSecondInterviewer();
        }
        return interview.getInterviewer();
    }

}
