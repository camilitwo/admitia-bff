package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.document.DocumentEntity;
import cl.mtn.admitiabff.domain.evaluation.EvaluationEntity;
import cl.mtn.admitiabff.domain.interview.InterviewEntity;
import cl.mtn.admitiabff.domain.student.StudentEntity;
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
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecturas que recorren {@code ApplicationEntity} y asociaciones LAZY (p. ej. {@code student}, {@code guardian})
 * deben ejecutarse dentro de una transacción para evitar {@code LazyInitializationException}.
 */
@Service
@Transactional(readOnly = true)
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
            .<Map<String, Object>>map(item -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("evaluationType", item.getEvaluationType()); m.put("status", item.getStatus().name()); m.put("score", item.getScore()); return m; })
            .toList();
        List<Map<String, Object>> interviews = interviewRepository.findByApplicationIdOrderByScheduledDateDesc(applicationId).stream()
            .<Map<String, Object>>map(item -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("interviewType", item.getInterviewType()); m.put("status", item.getStatus().name()); m.put("scheduledDate", item.getScheduledDate()); m.put("scheduledTime", item.getScheduledTime()); return m; })
            .toList();
        List<Map<String, Object>> documents = documentRepository.findByApplicationIdOrderByUploadDateDesc(applicationId).stream()
            .<Map<String, Object>>map(item -> Map.of("documentType", item.getDocumentType(), "approvalStatus", item.getApprovalStatus().name()))
            .toList();
        Map<String, Object> appMap = new LinkedHashMap<>();
        appMap.put("id", application.getId());
        appMap.put("status", application.getStatus().name());
        appMap.put("submissionDate", application.getSubmissionDate());
        appMap.put("studentName", application.getStudent().getFirstName() + " " + application.getStudent().getPaternalLastName() + " " + application.getStudent().getMaternalLastName());
        appMap.put("gradeApplied", application.getStudent().getGradeApplied());
        appMap.put("applicantEmail", application.getApplicantUser() == null ? null : application.getApplicantUser().getEmail());
        Map<String, Object> dataMap = new LinkedHashMap<>();
        dataMap.put("application", appMap);
        dataMap.put("evaluations", evaluations);
        dataMap.put("interviews", interviews);
        dataMap.put("documents", documents);
        Map<String, Object> summaryResult = new LinkedHashMap<>();
        summaryResult.put("success", true);
        summaryResult.put("data", dataMap);
        return summaryResult;
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
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("grade", grade == null ? "" : grade);
        filters.put("status", status == null ? "" : status);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", data.size());
        meta.put("academicYear", academicYear);
        meta.put("filters", filters);
        meta.put("sortBy", sortBy == null ? "studentName" : sortBy);
        meta.put("sortOrder", sortOrder == null ? "ASC" : sortOrder);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", data);
        result.put("meta", meta);
        return result;
    }

    public Map<String, Object> clearCache(String pattern) { Map<String, Object> r = new LinkedHashMap<>(); r.put("success", true); r.put("message", "No hay caché externo para limpiar"); r.put("pattern", pattern); return r; }
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
        boolean hasPending = completedEvaluations < totalApplications;
        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("type", hasPending ? "warning" : "performance");
        insight.put("message", hasPending ? "Hay evaluaciones pendientes por completar" : "El flujo de evaluaciones está al día");
        insight.put("action", hasPending ? "Revisar evaluaciones pendientes" : null);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalApplications", totalApplications);
        metrics.put("completedEvaluations", completedEvaluations);
        metrics.put("averageScore", String.valueOf(averageScore));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("insights", List.of(insight));
        data.put("metrics", metrics);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", data);
        return result;
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

    public Map<String, Object> courseApplicants(Integer academicYear) {
        int year = academicYear == null ? LocalDate.now().getYear() + 1 : academicYear;
        List<ApplicationEntity> apps = applicationRepository.findAll().stream()
            .filter(app -> app.getDeletedAt() == null && !app.isArchived())
            .filter(app -> app.getAcademicYear() != null && year == app.getAcademicYear())
            .toList();

        List<Map<String, Object>> rows = apps.stream()
            .map(app -> {
                StudentEntity student = app.getStudent();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("applicationId", app.getId());
                row.put("studentId", student.getId());
                row.put("studentName", fullStudentName(student));
                row.put("gradeApplied", student.getGradeApplied());
                row.put("gender", student.getGender());
                row.put("admissionPreference", admissionPreferenceLabel(student.getAdmissionPreference()));
                row.put("alumniChild", alumniChildText(student));
                row.put("siblingsInSchool", siblingsInSchoolText(student));
                row.put("examAverage", examAverage(app.getId()));
                row.put("cycleDirectorDecision", cycleDirectorDecision(app.getId()));
                row.put("status", app.getStatus().name());
                row.put("statusLabel", statusLabel(app.getStatus().name()));
                return row;
            })
            .sorted(Comparator.comparingInt(row -> gradeOrder((String) row.get("gradeApplied"))))
            .toList();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("academicYear", year);
        meta.put("total", rows.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", rows);
        result.put("meta", meta);
        return result;
    }

    public Map<String, Object> applicantCard(Long applicationId) {
        ApplicationEntity app = applicationRepository.findActiveById(applicationId)
            .orElseThrow(() -> new IllegalArgumentException("Postulación no encontrada"));
        StudentEntity student = app.getStudent();
        List<EvaluationEntity> evaluations = evaluationRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId);
        List<InterviewEntity> interviews = interviewRepository.findByApplicationIdOrderByScheduledDateDesc(applicationId);
        List<DocumentEntity> documents = documentRepository.findByApplicationIdOrderByUploadDateDesc(applicationId);

        List<Map<String, Object>> exams = evaluations.stream()
            .filter(item -> Set.of("LANGUAGE_EXAM", "MATHEMATICS_EXAM", "ENGLISH_EXAM").contains(item.getEvaluationType()))
            .map(item -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("evaluationType", item.getEvaluationType());
                m.put("subject", examSubjectLabel(item.getEvaluationType()));
                m.put("responsible", item.getEvaluator() == null ? "No asignado" : item.getEvaluator().getFirstName() + " " + item.getEvaluator().getLastName());
                m.put("score", item.getScore());
                m.put("maxScore", item.getMaxScore());
                m.put("percentage", examPercentage(item.getScore(), item.getMaxScore()));
                m.put("status", item.getStatus().name());
                m.put("reportLink", examReportLink(documents, item.getEvaluationType()));
                return m;
            })
            .toList();

        Map<String, Object> cycleInterview = new LinkedHashMap<>();
        EvaluationEntity directorInterview = evaluations.stream()
            .filter(item -> "CYCLE_DIRECTOR_INTERVIEW".equals(item.getEvaluationType()))
            .findFirst().orElse(null);
        EvaluationEntity directorReport = evaluations.stream()
            .filter(item -> "CYCLE_DIRECTOR_REPORT".equals(item.getEvaluationType()))
            .findFirst().orElse(null);
        EvaluationEntity interviewSource = directorInterview != null ? directorInterview : directorReport;
        if (interviewSource != null) {
            cycleInterview.put("date", interviewSource.getEvaluationDate());
            cycleInterview.put("done", interviewSource.getStatus() == EvaluationStatus.COMPLETED);
            cycleInterview.put("decision", cycleDirectorDecisionText(directorReport != null ? directorReport : interviewSource));
            cycleInterview.put("reportLink", findDocumentLink(documents, "cycle", "ciclo", "director"));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("observations", interviewSource.getObservations());
            report.put("recommendations", interviewSource.getRecommendations());
            report.put("areasForImprovement", interviewSource.getAreasForImprovement());
            report.put("evaluator", evaluatorName(interviewSource));
            cycleInterview.put("report", report);
        } else {
            cycleInterview.put("date", null);
            cycleInterview.put("done", false);
            cycleInterview.put("decision", "Pendiente");
            cycleInterview.put("reportLink", null);
            cycleInterview.put("report", null);
        }

        Map<String, Object> family = new LinkedHashMap<>();
        if (app.getFather() != null) {
            family.put("fatherName", app.getFather().getFullName());
            family.put("fatherEmail", app.getFather().getEmail());
            family.put("fatherPhone", app.getFather().getPhone());
        }
        if (app.getMother() != null) {
            family.put("motherName", app.getMother().getFullName());
            family.put("motherEmail", app.getMother().getEmail());
            family.put("motherPhone", app.getMother().getPhone());
        }
        if (app.getGuardian() != null) {
            family.put("guardianName", app.getGuardian().getFullName());
            family.put("guardianEmail", app.getGuardian().getEmail());
            family.put("guardianPhone", app.getGuardian().getPhone());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", app.getId());
        data.put("status", app.getStatus().name());
        data.put("statusLabel", statusLabel(app.getStatus().name()));
        data.put("submissionDate", app.getSubmissionDate());
        data.put("observations", app.getNotes());
        data.put("processType", isPrekinder(student.getGradeApplied()) ? "PREKÍNDER" : "KÍNDER–IV");
        data.put("examAverage", examAverage(applicationId));
        Map<String, Object> studentMap = new LinkedHashMap<>();
        studentMap.put("id", student.getId());
        studentMap.put("firstName", student.getFirstName());
        studentMap.put("lastName", fullStudentName(student));
        studentMap.put("rut", student.getRut());
        studentMap.put("birthDate", student.getBirthDate());
        studentMap.put("gender", student.getGender());
        studentMap.put("gradeApplied", student.getGradeApplied());
        studentMap.put("currentSchool", student.getCurrentSchool());
        studentMap.put("admissionPreference", admissionPreferenceLabel(student.getAdmissionPreference()));
        studentMap.put("isAlumniChild", student.isAlumniChild());
        studentMap.put("alumniParentYear", student.getAlumniParentYear());
        studentMap.put("hasSiblingsInSchool", student.isHasSiblingsInSchool());
        studentMap.put("siblingsInSchoolDetails", student.getSiblingsInSchoolDetails());
        data.put("student", studentMap);
        data.put("family", family);
        String questionnaireLink = findDocumentLink(documents, "questionnaire", "cuestionario", "family", "familia");
        Map<String, Object> familyQuestionnaire = new LinkedHashMap<>();
        familyQuestionnaire.put("received", questionnaireLink == null ? null : true);
        familyQuestionnaire.put("reportLink", questionnaireLink);
        data.put("familyQuestionnaire", familyQuestionnaire);
        data.put("prekinderProcess", prekinderProcess(evaluations, interviews, documents));
        data.put("exams", exams);
        data.put("cycleDirector", cycleInterview);
        data.put("familyInterview", familyInterviewScore(applicationId));
        data.put("documents", documents.stream().map(document -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", document.getDocumentType());
            item.put("name", document.getOriginalName() == null ? document.getFileName() : document.getOriginalName());
            item.put("url", document.getFilePath());
            return item;
        }).toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", data);
        return result;
    }

    private int academicYearOf(ApplicationEntity app) {
        LocalDateTime ref = app.getSubmissionDate() != null ? app.getSubmissionDate() : app.getCreatedAt();
        return ref == null ? LocalDate.now().getYear() + 1 : ref.getYear();
    }

    private String fullStudentName(StudentEntity student) {
        return (value(student.getFirstName()) + " " + value(student.getPaternalLastName()) + " " + value(student.getMaternalLastName())).trim();
    }

    private String value(String value) { return value == null ? "" : value; }

    private boolean isPrekinder(String grade) {
        if (grade == null) return false;
        String normalized = grade.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("prek") || normalized.contains("pre-k");
    }

    private Map<String, Object> prekinderProcess(List<EvaluationEntity> evaluations, List<InterviewEntity> interviews, List<DocumentEntity> documents) {
        EvaluationEntity assessment = evaluations.stream()
            .filter(item -> item.getEvaluationType() != null && item.getEvaluationType().toUpperCase(java.util.Locale.ROOT).contains("PSYCHOLOG"))
            .findFirst().orElse(null);
        InterviewEntity assessmentInterview = interviews.stream()
            .filter(item -> "PSYCHOLOGICAL".equalsIgnoreCase(item.getInterviewType()))
            .findFirst().orElse(null);
        InterviewEntity familyInterview = interviews.stream()
            .filter(item -> "FAMILY".equalsIgnoreCase(item.getInterviewType()))
            .findFirst().orElse(null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evaluationDate", assessmentInterview != null ? assessmentInterview.getScheduledDate() : assessment == null || assessment.getEvaluationDate() == null ? null : assessment.getEvaluationDate().toLocalDate());
        data.put("evaluationTime", assessmentInterview == null ? null : assessmentInterview.getScheduledTime());
        data.put("location", assessmentInterview == null ? null : assessmentInterview.getLocation());
        data.put("evaluator", assessment != null && assessment.getEvaluator() != null ? assessment.getEvaluator().getFirstName() + " " + assessment.getEvaluator().getLastName() : interviewerNames(assessmentInterview));
        data.put("attended", assessment == null ? null : assessment.getStatus() == EvaluationStatus.COMPLETED);
        data.put("evaluationReportLink", findDocumentLink(documents, "psych", "psico", "dap", "evaluacion"));
        data.put("familyInterviewDate", familyInterview == null ? null : familyInterview.getScheduledDate());
        data.put("familyInterviewers", interviewerNames(familyInterview));
        data.put("familyInterviewDone", familyInterview == null ? null : familyInterview.getStatus() == InterviewStatus.COMPLETED);
        data.put("familyInterviewReportLink", findDocumentLink(documents, "family_interview", "entrevista_famil", "informe_famil"));
        return data;
    }

    private String interviewerNames(InterviewEntity interview) {
        if (interview == null) return null;
        String first = interview.getInterviewer() == null ? "" : interview.getInterviewer().getFirstName() + " " + interview.getInterviewer().getLastName();
        String second = interview.getSecondInterviewer() == null ? "" : interview.getSecondInterviewer().getFirstName() + " " + interview.getSecondInterviewer().getLastName();
        String names = (first + (first.isBlank() || second.isBlank() ? "" : " y ") + second).trim();
        return names.isBlank() ? null : names;
    }

    private String examReportLink(List<DocumentEntity> documents, String evaluationType) {
        return switch (evaluationType) {
            case "LANGUAGE_EXAM" -> findDocumentLink(documents, "language", "lenguaje");
            case "MATHEMATICS_EXAM" -> findDocumentLink(documents, "mathematics", "matematica", "matemática");
            case "ENGLISH_EXAM" -> findDocumentLink(documents, "english", "ingles", "inglés");
            default -> null;
        };
    }

    private String findDocumentLink(List<DocumentEntity> documents, String... tokens) {
        return documents.stream().filter(document -> {
            String documentType = document.getDocumentType() == null ? "" : document.getDocumentType().name();
            String haystack = (documentType + " " + value(document.getFileName()) + " " + value(document.getOriginalName())).toLowerCase(java.util.Locale.ROOT);
            for (String token : tokens) if (haystack.contains(token.toLowerCase(java.util.Locale.ROOT))) return true;
            return false;
        }).map(DocumentEntity::getFilePath).filter(path -> path != null && !path.isBlank()).findFirst().orElse(null);
    }

    private String admissionPreferenceLabel(String preference) {
        if (preference == null) return "Nueva";
        return switch (preference) {
            case "HIJO_EX_ALUMNO" -> "Hijo/a exalumno";
            case "HIJO_FUNCIONARIO" -> "Hijo/a funcionario";
            case "NINGUNA", "NUEVA" -> "Nueva";
            default -> preference;
        };
    }

    private String alumniChildText(StudentEntity student) {
        if (!student.isAlumniChild()) return "No";
        String text = "Sí";
        if (student.getAlumniParentYear() != null) {
            text += " (G." + student.getAlumniParentYear() + ")";
        }
        return text;
    }

    private String siblingsInSchoolText(StudentEntity student) {
        if (!student.isHasSiblingsInSchool()) return "No";
        String text = "Sí";
        if (student.getSiblingsInSchoolDetails() != null && !student.getSiblingsInSchoolDetails().isBlank()) {
            text += " — " + student.getSiblingsInSchoolDetails();
        }
        return text;
    }

    private BigDecimal examAverage(Long applicationId) {
        List<EvaluationEntity> exams = evaluationRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId).stream()
            .filter(item -> Set.of("LANGUAGE_EXAM", "MATHEMATICS_EXAM", "ENGLISH_EXAM").contains(item.getEvaluationType()))
            .filter(item -> item.getScore() != null)
            .toList();
        if (exams.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (EvaluationEntity exam : exams) {
            BigDecimal max = exam.getMaxScore() == null || exam.getMaxScore().compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.valueOf(100) : exam.getMaxScore();
            sum = sum.add(exam.getScore().multiply(BigDecimal.valueOf(100)).divide(max, 2, java.math.RoundingMode.HALF_UP));
        }
        return sum.divide(BigDecimal.valueOf(exams.size()), 2, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal examPercentage(BigDecimal score, BigDecimal maxScore) {
        if (score == null) return null;
        BigDecimal max = maxScore == null || maxScore.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.valueOf(100) : maxScore;
        return score.multiply(BigDecimal.valueOf(100)).divide(max, 2, java.math.RoundingMode.HALF_UP);
    }

    private Map<String, Object> familyInterviewScore(Long applicationId) {
        List<EvaluationEntity> familyEvals = evaluationRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId).stream()
            .filter(item -> "FAMILY_INTERVIEW".equals(item.getEvaluationType()))
            .filter(item -> item.getStatus() == EvaluationStatus.COMPLETED)
            .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        if (familyEvals.isEmpty()) {
            result.put("percentage", null);
            result.put("count", 0);
            result.put("scores", List.of());
            return result;
        }

        List<BigDecimal> scores = familyEvals.stream()
            .map(EvaluationEntity::getFamilyInterviewScore)
            .filter(s -> s != null)
            .toList();

        result.put("count", scores.size());
        result.put("scores", scores);

        if (scores.isEmpty()) {
            result.put("percentage", null);
        } else {
            BigDecimal sum = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(scores.size()), 2, java.math.RoundingMode.HALF_UP);
            result.put("percentage", avg);
        }
        return result;
    }

    private String cycleDirectorDecision(Long applicationId) {
        return evaluationRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId).stream()
            .filter(item -> Set.of("CYCLE_DIRECTOR_INTERVIEW", "CYCLE_DIRECTOR_REPORT").contains(item.getEvaluationType()))
            .findFirst()
            .map(this::cycleDirectorDecisionText)
            .orElse("Pendiente");
    }

    private String cycleDirectorDecisionText(EvaluationEntity evaluation) {
        String recommendations = evaluation.getRecommendations();
        if (recommendations != null && !recommendations.isBlank()) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?im)^Decisi[oó]n(?:\\s+Final)?:\\s*(.+)$")
                .matcher(recommendations);
            if (matcher.find()) return matcher.group(1).trim();
        }
        return "Pendiente";
    }

    private String evaluatorName(EvaluationEntity evaluation) {
        if (evaluation.getEvaluator() == null) return null;
        return (value(evaluation.getEvaluator().getFirstName()) + " " + value(evaluation.getEvaluator().getLastName())).trim();
    }

    private String examSubjectLabel(String evaluationType) {
        return switch (evaluationType) {
            case "LANGUAGE_EXAM" -> "Lenguaje";
            case "MATHEMATICS_EXAM" -> "Matemáticas";
            case "ENGLISH_EXAM" -> "Inglés";
            default -> evaluationType;
        };
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "PENDING" -> "Pendiente";
            case "UNDER_REVIEW" -> "En Revisión";
            case "DOCUMENTS_REQUESTED" -> "Docs. Solicitados";
            case "INCOMPLETE" -> "Incompleta";
            case "INTERVIEW_SCHEDULED" -> "Entrevista Programada";
            case "EXAM_SCHEDULED" -> "Examen Programado";
            case "APPROVED" -> "Aceptado";
            case "REJECTED" -> "Rechazado";
            case "WAITLIST" -> "Lista de Espera";
            case "ARCHIVED" -> "Archivada";
            default -> status;
        };
    }

    private static final List<String> GRADE_ORDER = List.of(
        "prekinder", "prekínder", "pre-kinder", "pre-kínder", "kinder", "kínder",
        "1 basico", "1 básico", "1° basico", "1° básico", "1º basico", "1º básico",
        "2 basico", "2 básico", "2° basico", "2° básico", "2º basico", "2º básico",
        "3 basico", "3 básico", "3° basico", "3° básico", "3º basico", "3º básico",
        "4 basico", "4 básico", "4° basico", "4° básico", "4º basico", "4º básico",
        "5 basico", "5 básico", "5° basico", "5° básico", "5º basico", "5º básico",
        "6 basico", "6 básico", "6° basico", "6° básico", "6º basico", "6º básico",
        "7 basico", "7 básico", "7° basico", "7° básico", "7º basico", "7º básico",
        "8 basico", "8 básico", "8° basico", "8° básico", "8º basico", "8º básico",
        "i medio", "i° medio", "iº medio", "1 medio", "1° medio", "1º medio",
        "ii medio", "ii° medio", "iiº medio", "2 medio", "2° medio", "2º medio",
        "iii medio", "iii° medio", "iiiº medio", "3 medio", "3° medio", "3º medio",
        "iv medio", "iv° medio", "ivº medio", "4 medio", "4° medio", "4º medio"
    );

    private int gradeOrder(String grade) {
        if (grade == null) return Integer.MAX_VALUE;
        String normalized = grade.toLowerCase().replaceAll("[\\.\\-]", "").trim();
        int idx = GRADE_ORDER.indexOf(normalized);
        return idx >= 0 ? idx : Integer.MAX_VALUE;
    }
}
