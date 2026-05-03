package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.evaluation.EvaluationEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.util.CsvUtils;
import cl.mtn.admitiabff.util.JsonSupport;
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
    private final NotificationService notificationService;
    private final AuthService authService;
    private final JsonSupport jsonSupport;

    public EvaluationService(EvaluationRepository evaluationRepository, ApplicationRepository applicationRepository, InterviewRepository interviewRepository, UserRepository userRepository, NotificationService notificationService, AuthService authService, JsonSupport jsonSupport) {
        this.evaluationRepository = evaluationRepository;
        this.applicationRepository = applicationRepository;
        this.interviewRepository = interviewRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
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
        List<Map<String, Object>> evaluations = new java.util.ArrayList<>(
            evaluationRepository.findByEvaluatorIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse).toList()
        );
        List<InterviewStatus> excluded = List.of(InterviewStatus.CANCELLED, InterviewStatus.RESCHEDULED);
        interviewRepository.findVisibleForInterviewer(userId, excluded).stream()
            .map(this::interviewToEvaluationResponse)
            .forEach(evaluations::add);
        return Map.of("success", true, "data", evaluations, "count", evaluations.size());
    }

    private Map<String, Object> interviewToEvaluationResponse(cl.mtn.admitiabff.domain.interview.InterviewEntity entity) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entity.getId());
        response.put("applicationId", entity.getApplication() == null ? null : entity.getApplication().getId());
        response.put("evaluatorId", entity.getInterviewer() == null ? null : entity.getInterviewer().getId());
        response.put("evaluationType", entity.getInterviewType());
        response.put("type", entity.getInterviewType());
        response.put("status", entity.getStatus().name());
        response.put("scheduledDate", entity.getScheduledDate());
        response.put("evaluationDate", entity.getScheduledDate() != null && entity.getScheduledTime() != null
            ? entity.getScheduledDate().atTime(entity.getScheduledTime()) : null);
        response.put("score", null);
        response.put("maxScore", null);
        response.put("observations", entity.getNotes());
        response.put("recommendations", null);
        response.put("createdAt", entity.getCreatedAt());
        response.put("updatedAt", entity.getUpdatedAt());
        response.put("completedAt", null);
        if (entity.getApplication() != null && entity.getApplication().getStudent() != null) {
            var student = entity.getApplication().getStudent();
            response.put("studentName", student.getFirstName() + " " + student.getPaternalLastName() + " " + student.getMaternalLastName());
            response.put("gradeApplied", student.getGradeApplied());
        }
        return response;
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
        notificationService.recordEmail(Map.of("to", saved.getEvaluator().getEmail(), "subject", "Nueva evaluación asignada", "message", "Se le ha asignado una nueva evaluación", "type", "EVALUATION_ASSIGNMENT"));
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
        response.put("createdAt", entity.getCreatedAt());
        response.put("updatedAt", entity.getUpdatedAt());
        response.put("completedAt", entity.getCompletedAt());
        return response;
    }

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
}
