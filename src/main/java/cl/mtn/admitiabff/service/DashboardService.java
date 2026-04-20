package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.evaluation.EvaluationEntity;
import cl.mtn.admitiabff.domain.interview.InterviewEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.DocumentRepository;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.GuardianRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.InterviewerScheduleRepository;
import cl.mtn.admitiabff.repository.NotificationRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final GuardianRepository guardianRepository;
    private final NotificationRepository notificationRepository;
    private final EvaluationRepository evaluationRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewerScheduleRepository scheduleRepository;
    private final DocumentRepository documentRepository;

    public DashboardService(ApplicationRepository applicationRepository, UserRepository userRepository, GuardianRepository guardianRepository, NotificationRepository notificationRepository, EvaluationRepository evaluationRepository, InterviewRepository interviewRepository, InterviewerScheduleRepository scheduleRepository, DocumentRepository documentRepository) {
        this.applicationRepository = applicationRepository;
        this.userRepository = userRepository;
        this.guardianRepository = guardianRepository;
        this.notificationRepository = notificationRepository;
        this.evaluationRepository = evaluationRepository;
        this.interviewRepository = interviewRepository;
        this.scheduleRepository = scheduleRepository;
        this.documentRepository = documentRepository;
    }

    public Map<String, Object> generalStats() {
        return Map.of(
            "totalApplications", applicationRepository.countByDeletedAtIsNull(),
            "pendingApplications", applicationRepository.countByDeletedAtIsNullAndStatus(ApplicationStatus.PENDING),
            "approvedApplications", applicationRepository.countByDeletedAtIsNullAndStatus(ApplicationStatus.APPROVED),
            "rejectedApplications", applicationRepository.countByDeletedAtIsNullAndStatus(ApplicationStatus.REJECTED),
            "interviewsScheduled", interviewRepository.countByStatus(InterviewStatus.SCHEDULED),
            "evaluationsPending", evaluationRepository.countByStatusIn(List.of(EvaluationStatus.PENDING, EvaluationStatus.IN_PROGRESS))
        );
    }

    public Map<String, Object> adminStats() {
        return Map.of("success", true, "data", Map.of("applications", generalStats(), "users", Map.of("total", userRepository.count()), "guardians", Map.of("total", guardianRepository.count()), "notifications", Map.of("total", notificationRepository.count())));
    }

    public Map<String, Object> detailedAdminStats(Integer academicYear) {
        int year = academicYear == null ? LocalDate.now().getYear() : academicYear;
        List<cl.mtn.admitiabff.domain.application.ApplicationEntity> apps = applicationRepository.findAll().stream().filter(app -> app.getDeletedAt() == null && app.getCreatedAt() != null && app.getCreatedAt().getYear() == year).toList();
        Map<String, Long> statusBreakdown = apps.stream().collect(Collectors.groupingBy(app -> app.getStatus().name(), LinkedHashMap::new, Collectors.counting()));
        List<Map<String, Object>> gradeDistribution = apps.stream().collect(Collectors.groupingBy(app -> app.getStudent().getGradeApplied(), LinkedHashMap::new, Collectors.counting())).entrySet().stream()
            .<Map<String, Object>>map(entry -> Map.of("grade", entry.getKey(), "count", entry.getValue()))
            .toList();
        List<Map<String, Object>> monthlyTrends = apps.stream().collect(Collectors.groupingBy(app -> app.getCreatedAt().getYear() + "-" + String.format("%02d", app.getCreatedAt().getMonthValue()), LinkedHashMap::new, Collectors.counting())).entrySet().stream()
            .<Map<String, Object>>map(entry -> Map.of("month", entry.getKey(), "total", entry.getValue()))
            .toList();
        long weeklyScheduled = interviewRepository.findForCalendar(LocalDate.now().minusDays(7), LocalDate.now().plusDays(7)).stream().filter(item -> item.getStatus() == InterviewStatus.SCHEDULED).count();
        long weeklyCompleted = interviewRepository.findForCalendar(LocalDate.now().minusDays(7), LocalDate.now().plusDays(7)).stream().filter(item -> item.getStatus() == InterviewStatus.COMPLETED).count();
        List<Map<String, Object>> pendingEvaluations = evaluationRepository.findAssignments(List.of(EvaluationStatus.PENDING, EvaluationStatus.IN_PROGRESS)).stream().collect(Collectors.groupingBy(EvaluationEntity::getEvaluationType, LinkedHashMap::new, Collectors.counting())).entrySet().stream()
            .<Map<String, Object>>map(entry -> Map.of("evaluationType", entry.getKey(), "count", entry.getValue()))
            .toList();
        List<Integer> availableYears = applicationRepository.findAll().stream().filter(app -> app.getCreatedAt() != null).map(app -> app.getCreatedAt().getYear()).distinct().sorted(java.util.Comparator.reverseOrder()).toList();
        return Map.of("success", true, "data", Map.of("academicYear", year, "statusBreakdown", statusBreakdown, "gradeDistribution", gradeDistribution, "monthlyTrends", monthlyTrends, "weeklyInterviews", Map.of("scheduled", weeklyScheduled, "completed", weeklyCompleted), "pendingEvaluations", Map.of("total", pendingEvaluations.stream().mapToLong(item -> ((Number) item.get("count")).longValue()).sum(), "items", pendingEvaluations), "availableYears", availableYears));
    }

    public Map<String, Object> applicantSummary(Long applicationId) {
        var application = applicationRepository.findActiveById(applicationId).orElseThrow(() -> new IllegalArgumentException("Postulación no encontrada"));
        List<Map<String, Object>> evaluations = evaluationRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId).stream()
            .<Map<String, Object>>map(item -> Map.of("evaluationType", item.getEvaluationType(), "status", item.getStatus().name(), "score", item.getScore()))
            .toList();
        List<Map<String, Object>> interviews = interviewRepository.findByApplicationIdOrderByScheduledDateDesc(applicationId).stream()
            .<Map<String, Object>>map(item -> Map.of("interviewType", item.getInterviewType(), "status", item.getStatus().name(), "scheduledDate", item.getScheduledDate(), "scheduledTime", item.getScheduledTime()))
            .toList();
        List<Map<String, Object>> documents = documentRepository.findByApplicationIdOrderByUploadDateDesc(applicationId).stream()
            .<Map<String, Object>>map(item -> Map.of("documentType", item.getDocumentType(), "approvalStatus", item.getApprovalStatus().name()))
            .toList();
        return Map.of("success", true, "data", Map.of("application", Map.of("id", application.getId(), "status", application.getStatus().name(), "submissionDate", application.getSubmissionDate(), "studentName", application.getStudent().getFirstName() + " " + application.getStudent().getPaternalLastName() + " " + application.getStudent().getMaternalLastName(), "gradeApplied", application.getStudent().getGradeApplied(), "applicantEmail", application.getApplicantUser() == null ? null : application.getApplicantUser().getEmail()), "evaluations", evaluations, "interviews", interviews, "documents", documents));
    }

    public Map<String, Object> applicantMetrics(Integer academicYear, String grade, String status, String sortBy, String sortOrder) {
        List<cl.mtn.admitiabff.domain.application.ApplicationEntity> apps = applicationRepository.findAll().stream()
            .filter(app -> app.getDeletedAt() == null)
            .filter(app -> academicYear == null || app.getCreatedAt().getYear() == academicYear)
            .filter(app -> grade == null || grade.isBlank() || grade.equals(app.getStudent().getGradeApplied()))
            .filter(app -> status == null || status.isBlank() || status.equals(app.getStatus().name()))
            .toList();
        List<Map<String, Object>> data = apps.stream().map(app -> {
            long approved = documentRepository.countByApplicationIdAndApprovalStatus(app.getId(), cl.mtn.admitiabff.domain.common.DocumentApprovalStatus.APPROVED);
            long total = documentRepository.countByApplicationId(app.getId());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("applicationId", app.getId());
            response.put("studentId", app.getStudent().getId());
            response.put("studentName", app.getStudent().getFirstName() + " " + app.getStudent().getPaternalLastName() + " " + app.getStudent().getMaternalLastName());
            response.put("gradeApplied", app.getStudent().getGradeApplied());
            response.put("applicationStatus", app.getStatus().name());
            response.put("applicationDate", app.getSubmissionDate());
            response.put("guardianName", app.getGuardian() == null ? null : app.getGuardian().getFullName());
            response.put("guardianEmail", app.getGuardian() == null ? null : app.getGuardian().getEmail());
            response.put("examScores", Map.of("mathematics", 0, "language", 0, "english", 0, "completionRate", 0));
            response.put("familyInterviews", interviewRepository.findByApplicationIdOrderByScheduledDateDesc(app.getId()).stream().map(InterviewEntity::getInterviewType).toList());
            response.put("documents", Map.of("approved", approved, "total", total, "completionRate", total == 0 ? 0 : (approved * 100.0) / total));
            return response;
        }).toList();
        return Map.of("success", true, "data", data, "meta", Map.of("total", data.size(), "academicYear", academicYear, "filters", Map.of("grade", grade == null ? "" : grade, "status", status == null ? "" : status), "sortBy", sortBy, "sortOrder", sortOrder));
    }

    public Map<String, Object> clearCache(String pattern) { return Map.of("success", true, "message", "No hay caché externo para limpiar", "pattern", pattern); }
    public Map<String, Object> cacheStats() { return Map.of("success", true, "data", Map.of("provider", "in-process", "entries", 0, "hits", 0, "misses", 0)); }
    public Map<String, Object> analyticsDashboardMetrics() { return Map.of("totalApplications", applicationRepository.countByDeletedAtIsNull(), "applicationsThisMonth", applicationRepository.findBetween(LocalDate.now().withDayOfMonth(1).atStartOfDay(), LocalDate.now().plusDays(1).atStartOfDay()).size(), "conversionRate", 0, "acceptedApplications", applicationRepository.countByDeletedAtIsNullAndStatus(ApplicationStatus.APPROVED), "averageCompletionDays", 0, "activeEvaluators", userRepository.findByRoleInOrderByRoleAscFirstNameAscLastNameAsc(List.of(cl.mtn.admitiabff.domain.common.Role.TEACHER, cl.mtn.admitiabff.domain.common.Role.PSYCHOLOGIST, cl.mtn.admitiabff.domain.common.Role.CYCLE_DIRECTOR, cl.mtn.admitiabff.domain.common.Role.COORDINATOR, cl.mtn.admitiabff.domain.common.Role.INTERVIEWER)).size(), "totalActiveUsers", userRepository.countByActiveTrue()); }

    public Map<String, Object> statusDistribution() {
        Map<String, Long> statusCount = applicationRepository.findAll().stream().filter(app -> app.getDeletedAt() == null).collect(Collectors.groupingBy(app -> app.getStatus().name(), LinkedHashMap::new, Collectors.counting()));
        long total = statusCount.values().stream().mapToLong(value -> ((Number) value).longValue()).sum();
        Map<String, Double> percentages = new LinkedHashMap<>();
        statusCount.forEach((key, value) -> percentages.put(key, total == 0 ? 0 : (((Number) value).doubleValue() * 100.0) / total));
        return Map.of("statusCount", statusCount, "statusPercentages", percentages, "totalApplications", total);
    }

    public Map<String, Object> temporalTrends() {
        Map<String, Integer> monthlyApplications = new LinkedHashMap<>();
        applicationRepository.findAll().stream().filter(app -> app.getDeletedAt() == null && app.getCreatedAt() != null && app.getCreatedAt().isAfter(java.time.LocalDateTime.now().minusMonths(12))).forEach(app -> monthlyApplications.merge(app.getCreatedAt().getYear() + "-" + String.format("%02d", app.getCreatedAt().getMonthValue()), 1, Integer::sum));
        return Map.of("success", true, "data", Map.of("trends", Map.of("monthlyApplications", monthlyApplications, "currentMonthApplications", monthlyApplications.values().stream().reduce((a, b) -> b).orElse(0), "lastMonthApplications", monthlyApplications.values().stream().skip(Math.max(0, monthlyApplications.size() - 2)).findFirst().orElse(0), "monthlyGrowthRate", 0)));
    }

    public Map<String, Object> gradeDistribution() {
        Map<String, Long> gradeCount = applicationRepository.findAll().stream().filter(app -> app.getDeletedAt() == null).collect(Collectors.groupingBy(app -> app.getStudent().getGradeApplied(), LinkedHashMap::new, Collectors.counting()));
        long total = gradeCount.values().stream().mapToLong(value -> ((Number) value).longValue()).sum();
        Map<String, Double> gradePercentages = new LinkedHashMap<>();
        gradeCount.forEach((key, value) -> gradePercentages.put(key, total == 0 ? 0 : (((Number) value).doubleValue() * 100.0) / total));
        return Map.of("success", true, "data", gradeCount.entrySet().stream()
                .<Map<String, Object>>map(entry -> Map.of("grade", entry.getKey(), "count", entry.getValue()))
                .toList(), "gradeCount", gradeCount, "gradePercentages", gradePercentages, "totalApplications", total);
    }

    public Map<String, Object> insights() {
        long totalApplications = applicationRepository.countByDeletedAtIsNull();
        long completedEvaluations = evaluationRepository.findAll().stream().filter(item -> item.getStatus() == EvaluationStatus.COMPLETED).count();
        BigDecimal averageScore = evaluationRepository.averageScore();
        return Map.of("success", true, "data", Map.of("insights", List.of(Map.of("type", completedEvaluations < totalApplications ? "warning" : "performance", "message", completedEvaluations < totalApplications ? "Hay evaluaciones pendientes por completar" : "El flujo de evaluaciones está al día", "action", completedEvaluations < totalApplications ? "Revisar evaluaciones pendientes" : null)), "metrics", Map.of("totalApplications", totalApplications, "completedEvaluations", completedEvaluations, "averageScore", String.valueOf(averageScore))));
    }

    public Map<String, Object> evaluatorAnalysis() {
        List<Map<String, Object>> data = userRepository.findByRoleInOrderByRoleAscFirstNameAscLastNameAsc(List.of(cl.mtn.admitiabff.domain.common.Role.TEACHER, cl.mtn.admitiabff.domain.common.Role.PSYCHOLOGIST, cl.mtn.admitiabff.domain.common.Role.CYCLE_DIRECTOR, cl.mtn.admitiabff.domain.common.Role.COORDINATOR, cl.mtn.admitiabff.domain.common.Role.INTERVIEWER)).stream().map(user -> {
            List<EvaluationEntity> evaluations = evaluationRepository.findByEvaluatorIdOrderByCreatedAtDesc(user.getId());
            long completed = evaluations.stream().filter(item -> item.getStatus() == EvaluationStatus.COMPLETED).count();
            long pending = evaluations.stream().filter(item -> item.getStatus() == EvaluationStatus.PENDING || item.getStatus() == EvaluationStatus.IN_PROGRESS).count();
            BigDecimal average = evaluations.stream().map(EvaluationEntity::getScore).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            long counted = evaluations.stream().map(EvaluationEntity::getScore).filter(java.util.Objects::nonNull).count();
            return Map.<String, Object>of("evaluatorId", user.getId(), "name", user.getFirstName() + " " + user.getLastName(), "total", evaluations.size(), "completed", completed, "pending", pending, "averageScore", counted == 0 ? BigDecimal.ZERO : average.divide(BigDecimal.valueOf(counted), 2, java.math.RoundingMode.HALF_UP));
        }).toList();
        return Map.of("success", true, "data", data);
    }

    public Map<String, Object> performanceMetrics() {
        return Map.of("success", true, "data", Map.of("applications", statusDistribution().get("statusCount"), "evaluations", evaluationRepository.countByStatus().stream().collect(Collectors.toMap(EvaluationRepository.KeyCountView::getKey, EvaluationRepository.KeyCountView::getTotal)), "interviews", interviewRepository.countByStatus().stream().collect(Collectors.toMap(InterviewRepository.KeyCountView::getKey, InterviewRepository.KeyCountView::getTotal)), "averageProcessingDays", 0));
    }
}
