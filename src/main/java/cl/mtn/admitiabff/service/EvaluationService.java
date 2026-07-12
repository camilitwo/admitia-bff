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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EvaluationService {
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
        List<InterviewStatus> excluded = List.of(InterviewStatus.CANCELLED, InterviewStatus.RESCHEDULED);
        interviewRepository.findVisibleForInterviewer(userId, excluded).stream()
            .forEach(interview -> addLinkedInterviewEvaluations(evaluations, addedKeys, interview));
        return Map.of("success", true, "data", evaluations, "count", evaluations.size());
    }

    private void addLinkedInterviewEvaluations(
        List<Map<String, Object>> evaluations,
        java.util.Set<String> addedKeys,
        cl.mtn.admitiabff.domain.interview.InterviewEntity interview
    ) {
        Long applicationId = interview.getApplication() == null ? null : interview.getApplication().getId();
        if (applicationId == null) return;

        for (String evaluationType : mapInterviewTypesToEvaluationTypes(interview.getInterviewType())) {
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
        if (interview.getInterviewer() != null) {
            response.put("evaluatorId", interview.getInterviewer().getId());
            response.put("evaluator", evaluatorMap(interview.getInterviewer()));
        }
        if (interview.getApplication() != null && interview.getApplication().getStudent() != null) {
            var student = interview.getApplication().getStudent();
            response.put("studentName", student.getFirstName() + " " + student.getPaternalLastName() + " " + student.getMaternalLastName());
            response.put("gradeApplied", student.getGradeApplied());
        }
    }
    public Map<String, Object> familyInterviewTemplate(String grade) { return Map.of("success", true, "data", Map.of("grade", grade, "sections", List.of("Historia familiar", "Motivación", "Rutinas", "Observaciones"))); }
    public Map<String, Object> get(Long id) { return toResponse(load(id)); }
    public Map<String, Object> familyInterviewData(Long evaluationId) { EvaluationEntity entity = load(evaluationId); return Map.of("success", true, "data", jsonSupport.readMap(entity.getInterviewData()), "score", entity.getFamilyInterviewScore()); }

    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        EvaluationEntity entity = new EvaluationEntity();
        merge(entity, payload);
        return Map.of("success", true, "message", "Evaluación creada correctamente", "data", toResponse(evaluationRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> payload) {
        EvaluationEntity entity = load(id);
        merge(entity, payload);
        return Map.of("success", true, "message", "Evaluación actualizada", "data", toResponse(evaluationRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        evaluationRepository.deleteById(id);
        return Map.of("success", true, "message", "Evaluación eliminada correctamente");
    }

    @Transactional
    public Map<String, Object> complete(Long id, Map<String, Object> payload) {
        EvaluationEntity entity = load(id);
        entity.setStatus(EvaluationStatus.COMPLETED);
        entity.setScore(decimalValue(payload.get("score")));
        entity.setMaxScore(decimalValue(payload.get("maxScore")));
        entity.setRecommendations(stringValue(payload.get("recommendations")));
        entity.setObservations(stringValue(payload.getOrDefault("observations", payload.get("comments"))));
        entity.setAreasForImprovement(stringValue(payload.get("areasForImprovement")));
        entity.setCompletedAt(LocalDateTime.now());
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
                        ? String.valueOf(saved.getApplication().getStudent().getGradeApplied())
                        : "";
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("evaluatorName", ev.getFirstName() == null ? "" : ev.getFirstName());
                data.put("studentName", studentName);
                data.put("gradeApplied", gradeApplied);
                data.put("evaluationType", saved.getSubject() == null ? "" : saved.getSubject());
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
        EvaluationEntity entity = load(evaluationId);
        Map<String, Object> interviewData = payload.get("interviewData") instanceof Map<?, ?> map ? (Map<String, Object>) map : payload;
        BigDecimal score = calculateInterviewScore(interviewData);
        entity.setInterviewData(jsonSupport.write(interviewData));
        entity.setFamilyInterviewScore(score);
        entity.setStatus(EvaluationStatus.COMPLETED);
        entity.setCompletedAt(LocalDateTime.now());
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
        entity.setRecommendations(stringValue(payload.getOrDefault("recommendations", entity.getRecommendations())));
        entity.setObservations(stringValue(payload.getOrDefault("observations", entity.getObservations())));
        entity.setAreasForImprovement(stringValue(payload.getOrDefault("areasForImprovement", entity.getAreasForImprovement())));

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
        response.put("recommendations", entity.getRecommendations());
        response.put("observations", entity.getObservations());
        response.put("areasForImprovement", entity.getAreasForImprovement());
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
        List<BigDecimal> values = interviewData.values().stream().filter(Number.class::isInstance).map(Number.class::cast).map(value -> BigDecimal.valueOf(value.doubleValue())).toList();
        if (values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, java.math.RoundingMode.HALF_UP);
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

    private String evaluationKey(Long applicationId, String evaluationType) {
        if (applicationId == null || evaluationType == null || evaluationType.isBlank()) return null;
        return applicationId + ":" + evaluationType;
    }

    private List<String> mapInterviewTypesToEvaluationTypes(String interviewType) {
        if (interviewType == null) return List.of();
        return switch (interviewType) {
            case "FAMILY" -> List.of("FAMILY_INTERVIEW");
            case "CYCLE_DIRECTOR" -> List.of("CYCLE_DIRECTOR_INTERVIEW", "CYCLE_DIRECTOR_REPORT");
            case "PSYCHOLOGICAL" -> List.of("PSYCHOLOGICAL_INTERVIEW");
            default -> List.of(interviewType);
        };
    }

}
