package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.application.ComplementaryFormEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.common.DocumentApprovalStatus;
import cl.mtn.admitiabff.domain.common.PaymentStatus;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.document.DocumentEntity;
import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import cl.mtn.admitiabff.domain.person.GuardianEntity;
import cl.mtn.admitiabff.domain.person.ParentEntity;
import cl.mtn.admitiabff.domain.person.SupporterEntity;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.ComplementaryFormRepository;
import cl.mtn.admitiabff.repository.DocumentRepository;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.GradeAvailabilityRepository;
import cl.mtn.admitiabff.repository.GuardianRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.ParentRepository;
import cl.mtn.admitiabff.repository.StudentRepository;
import cl.mtn.admitiabff.repository.SupporterRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.util.CsvUtils;
import cl.mtn.admitiabff.util.EmailDisplayFormatter;
import cl.mtn.admitiabff.util.JsonSupport;
import cl.mtn.admitiabff.util.TemplateUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class ApplicationService {
    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private final ApplicationRepository applicationRepository;
    private final StudentRepository studentRepository;
    private final ParentRepository parentRepository;
    private final GuardianRepository guardianRepository;
    private final SupporterRepository supporterRepository;
    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final ComplementaryFormRepository complementaryFormRepository;
    private final EvaluationRepository evaluationRepository;
    private final InterviewRepository interviewRepository;
    private final AuthService authService;
    private final NotificationService notificationService;
    private final cl.mtn.admitiabff.service.notification.EmailComposerService emailComposerService;
    private final JsonSupport jsonSupport;
    private final String uploadsDir;
    @org.springframework.beans.factory.annotation.Autowired
    private GradeAvailabilityRepository gradeAvailabilityRepository;

    public ApplicationService(ApplicationRepository applicationRepository, StudentRepository studentRepository, ParentRepository parentRepository, GuardianRepository guardianRepository, SupporterRepository supporterRepository, UserRepository userRepository, DocumentRepository documentRepository, ComplementaryFormRepository complementaryFormRepository, EvaluationRepository evaluationRepository, InterviewRepository interviewRepository, AuthService authService, NotificationService notificationService, cl.mtn.admitiabff.service.notification.EmailComposerService emailComposerService, JsonSupport jsonSupport, @Value("${app.uploads-dir}") String uploadsDir) {
        this.applicationRepository = applicationRepository;
        this.studentRepository = studentRepository;
        this.parentRepository = parentRepository;
        this.guardianRepository = guardianRepository;
        this.supporterRepository = supporterRepository;
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.complementaryFormRepository = complementaryFormRepository;
        this.evaluationRepository = evaluationRepository;
        this.interviewRepository = interviewRepository;
        this.authService = authService;
        this.notificationService = notificationService;
        this.emailComposerService = emailComposerService;
        this.jsonSupport = jsonSupport;
        this.uploadsDir = uploadsDir;
    }

    public Map<String, Object> stats() {
        long total = applicationRepository.countByDeletedAtIsNull();
        return Map.of("success", true, "data", Map.of(
            "totalApplications", total,
            "pendingApplications", applicationRepository.countByDeletedAtIsNullAndStatus(ApplicationStatus.PENDING),
            "approvedApplications", applicationRepository.countByDeletedAtIsNullAndStatus(ApplicationStatus.APPROVED),
            "rejectedApplications", applicationRepository.countByDeletedAtIsNullAndStatus(ApplicationStatus.REJECTED),
            "interviewsScheduled", interviewRepository.countByStatus(cl.mtn.admitiabff.domain.common.InterviewStatus.SCHEDULED),
            "examsScheduled", evaluationRepository.countByStatusIn(List.of(cl.mtn.admitiabff.domain.common.EvaluationStatus.PENDING, cl.mtn.admitiabff.domain.common.EvaluationStatus.IN_PROGRESS)),
            "averageProcessingDays", 0
        ));
    }

    public Map<String, Object> publicAll(int page, int limit) {
        List<Map<String, Object>> data = applicationRepository.findByDeletedAtIsNullOrderBySubmissionDateDesc(PageRequest.of(page, limit)).stream().map(this::toPublicResponse).toList();
        return Map.of("success", true, "data", data, "pagination", Map.of("page", page, "limit", limit, "total", applicationRepository.countByDeletedAtIsNull()));
    }

    public Map<String, Object> contact(Long id) {
        ApplicationEntity application = load(id);
        return Map.of("success", true, "data", Map.of(
            "applicationId", application.getId(),
            "applicantUser", application.getApplicantUser() == null ? null : Map.of("email", application.getApplicantUser().getEmail(), "firstName", application.getApplicantUser().getFirstName(), "lastName", application.getApplicantUser().getLastName()),
            "guardian", application.getGuardian() == null ? null : Map.of("email", application.getGuardian().getEmail(), "fullName", application.getGuardian().getFullName()),
            "father", application.getFather() == null ? null : Map.of("email", application.getFather().getEmail(), "fullName", application.getFather().getFullName()),
            "mother", application.getMother() == null ? null : Map.of("email", application.getMother().getEmail(), "fullName", application.getMother().getFullName()),
            "studentName", fullStudentName(application.getStudent())
        ));
    }

    public Map<String, Object> list(Integer page, Integer size, String status, String gradeApplying, String search) {
        Page<ApplicationEntity> result = applicationRepository.search(parseStatus(status), emptyToNull(gradeApplying), emptyToNull(search), PageRequest.of(page == null ? 0 : page, size == null ? 15 : size));
        List<Map<String, Object>> data = result.getContent().stream().map(this::toDataTableResponse).toList();
        return Map.of("success", true, "count", result.getTotalElements(), "data", data);
    }

    public Map<String, Object> recent(int limit) {
        List<Map<String, Object>> data = applicationRepository.findByDeletedAtIsNullOrderBySubmissionDateDesc(PageRequest.of(0, limit)).stream().map(this::toSummaryResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    public Map<String, Object> requiringDocuments() {
        List<Map<String, Object>> data = applicationRepository.findByDeletedAtIsNullAndStatusOrderBySubmissionDateAsc(ApplicationStatus.DOCUMENTS_REQUESTED).stream().map(this::toSummaryResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    public Map<String, Object> search(String query, String status) {
        Page<ApplicationEntity> result = applicationRepository.search(parseStatus(status), null, emptyToNull(query), PageRequest.of(0, 50));
        List<Map<String, Object>> data = result.getContent().stream().map(this::toSummaryResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size(), "query", query);
    }

    public ResponseEntity<?> export(String status, String format, String search) {
        List<Map<String, Object>> data = applicationRepository.search(parseStatus(status), null, emptyToNull(search), PageRequest.of(0, 1000)).getContent().stream().map(this::toSummaryResponse).toList();
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(CsvUtils.toCsv(data));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    public Map<String, Object> byStatus(String status, int page, int limit) {
        return list(page, limit, status, null, null);
    }

    public Map<String, Object> byUser(Long userId) {
        List<Map<String, Object>> data = applicationRepository.findByDeletedAtIsNullAndApplicantUserIdOrderByCreatedAtDesc(userId).stream().map(this::toFullResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    public Map<String, Object> myApplications() {
        return byUser(authService.requireAuth().id());
    }

    public Map<String, Object> forEvaluation(Long evaluatorId) {
        List<Map<String, Object>> data = applicationRepository.findForEvaluator(evaluatorId).stream().map(this::toSummaryResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    public Map<String, Object> specialCategory(String category) {
        List<Map<String, Object>> data = applicationRepository.findBySpecialCategory(category.toLowerCase()).stream().map(this::toSummaryResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size(), "category", category);
    }

    public Map<String, Object> get(Long id) {
        return toFullResponse(load(id));
    }

    public Map<String, Object> documents(Long applicationId) {
        List<Map<String, Object>> docs = documentRepository.findByApplicationIdOrderByUploadDateDesc(applicationId).stream().map(this::toDocumentResponse).toList();
        return Map.of("success", true, "data", docs, "count", docs.size());
    }

    public Map<String, Object> complementaryForm(Long applicationId) {
        return complementaryFormRepository.findByApplicationId(applicationId)
            .map(this::toComplementaryFormResponse)
            .orElseGet(() -> Map.of("success", true, "data", Map.of()));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        ApplicationEntity entity = new ApplicationEntity();
        entity.setStudent(resolveStudent(payload));

        // Validar vacante disponible para el nivel seleccionado según género
        String gradeApplied = entity.getStudent().getGradeApplied();
        String gender = entity.getStudent().getGender();
        if (gradeApplied != null && gender != null) {
            boolean hasVacancy = switch (gender) {
                case "MALE" -> gradeAvailabilityRepository.existsByGradeLevelAndHasVacancyMTrue(gradeApplied);
                case "FEMALE" -> gradeAvailabilityRepository.existsByGradeLevelAndHasVacancyFTrue(gradeApplied);
                default -> false;
            };
            if (!hasVacancy) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay vacantes disponibles para el nivel seleccionado: " + gradeApplied);
            }
        }

        entity.setFather(resolveParent(payload, "father", "parent1", "FATHER", true));
        entity.setMother(resolveParent(payload, "mother", "parent2", "MOTHER", false));
        entity.setGuardian(resolveGuardian(payload));
        entity.setSupporter(resolveSupporter(payload));
        entity.setApplicantUser(resolveApplicant(payload));
        entity.setStatus(parseStatus(value(payload.getOrDefault("status", "PENDING"))));
        entity.setNotes(value(payload.get("notes")));
        entity.setSubmissionDate(LocalDateTime.now());
        Object academicYearObj = payload.get("academicYear");
        if (academicYearObj != null) {
            entity.setAcademicYear(Integer.valueOf(academicYearObj.toString()));
        } else {
            entity.setAcademicYear(LocalDate.now().getYear() + 1);
        }
        ApplicationEntity saved = applicationRepository.save(entity);
        return Map.of("success", true, "message", "Postulación creada correctamente", "data", toFullResponse(saved));
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> payload) {
        ApplicationEntity entity = load(id);
        if (payload.containsKey("status")) entity.setStatus(parseStatus(value(payload.get("status"))));
        if (payload.containsKey("notes")) entity.setNotes(value(payload.get("notes")));
        if (payload.containsKey("studentGender")) {
            StudentEntity student = entity.getStudent();
            student.setGender(value(payload.get("studentGender")));
        }
        return Map.of("success", true, "message", "Postulación actualizada correctamente", "data", toFullResponse(applicationRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> updateStatus(Long id, Map<String, Object> payload) {
        ApplicationEntity entity = load(id);
        entity.setStatus(parseStatus(value(payload.getOrDefault("status", entity.getStatus().name()))));
        if (payload.containsKey("notes")) entity.setNotes(value(payload.get("notes")));
        return Map.of("success", true, "message", "Estado actualizado correctamente", "data", toFullResponse(applicationRepository.save(entity)));
    }

    /**
     * Compatibilidad con el front ({@code POST .../final-decision} con {@code decision}, {@code note}).
     * Paridad funcional con Node: {@code PATCH /api/applications/:id/status} + roles ADMIN/COORDINATOR
     * y notificación {@code ADMISSION_RESULT}. La decisión no se revierte si el correo falla,
     * pero la respuesta informa el resultado real del intento para que el front no muestre
     * una confirmación engañosa.
     */
    @Transactional
    public Map<String, Object> recordFinalDecision(Long id, Map<String, Object> payload) {
        var auth = authService.requireAuth();
        log.info("[final-decision] applicationId={} requested by userId={} email={} role={}",
            id, auth.id(), auth.email(), auth.role());
        if (!authService.hasAnyRoleContext(auth, Role.ADMIN, Role.COORDINATOR)) {
            log.warn("[final-decision] DENIED applicationId={} role={} (need ADMIN or COORDINATOR)", id, auth.role());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo administradores o coordinadores pueden registrar la decisión final");
        }
        String decisionRaw = firstNonNull(payload.get("decision"), payload.get("status"));
        if (decisionRaw == null || decisionRaw.isBlank()) {
            throw new IllegalArgumentException("Se requiere decision (APPROVED, WAITLIST o REJECTED)");
        }
        ApplicationStatus newStatus;
        try {
            newStatus = ApplicationStatus.valueOf(decisionRaw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("decision debe ser APPROVED, WAITLIST o REJECTED");
        }
        if (newStatus != ApplicationStatus.APPROVED
                && newStatus != ApplicationStatus.WAITLIST
                && newStatus != ApplicationStatus.REJECTED) {
            throw new IllegalArgumentException("decision debe ser APPROVED, WAITLIST o REJECTED");
        }
        String note = firstNonNull(payload.get("note"), payload.get("notes"));
        String noteOrEmpty = note == null || note.isBlank() ? null : note.trim();

        ApplicationEntity entity = load(id);
        ApplicationStatus previousStatus = entity.getStatus();
        entity.setStatus(newStatus);
        if (noteOrEmpty != null) {
            entity.setNotes(noteOrEmpty);
        }
        ApplicationEntity saved = applicationRepository.save(entity);

        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("attempted", false);
        notification.put("sent", false);
        notification.put("recipient", null);
        notification.put("status", "NOT_ATTEMPTED");
        notification.put("message", "No se encontró un email de contacto para la postulación");

        String to = resolveApplicationRecipientEmail(saved);
        if (to != null) {
            notification.put("attempted", true);
            notification.put("recipient", to);
            try {
                String studentName = saved.getStudent() != null
                        ? (safe(saved.getStudent().getFirstName()) + " "
                                + safe(saved.getStudent().getPaternalLastName()) + " "
                                + safe(saved.getStudent().getMaternalLastName())).trim()
                        : "";
                String displayStudentName = personNameForEmail(studentName);
                if (displayStudentName == null) displayStudentName = "el o la postulante";
                String familyNames = resolveFamilyNamesForEmail(saved);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("applicationId", id);
                data.put("studentName", escapeHtml(displayStudentName));
                String gradeApplied = saved.getStudent() == null
                        ? ""
                        : EmailDisplayFormatter.grade(saved.getStudent().getGradeApplied());
                data.put("gradeApplied", escapeHtml(gradeApplied.isBlank() ? "Curso no informado" : gradeApplied));
                data.put("familyNames", escapeHtml(familyNames.isBlank()
                        ? "de " + displayStudentName
                        : familyNames));
                data.put("parentNames", escapeHtml(familyNames.isBlank() ? "apoderado/a" : familyNames));
                data.put("previousStatus", applicationStatusLabel(previousStatus));
                data.put("currentStatus", applicationStatusLabel(newStatus));
                data.put("result", applicationStatusLabel(newStatus));
                data.put("resultBackground", decisionResultBackground(newStatus));
                data.put("resultColor", decisionResultColor(newStatus));
                data.put("resultBorder", decisionResultBorder(newStatus));
                data.put("message", formatEmailMessage(
                        noteOrEmpty == null ? defaultDecisionMessage(newStatus) : noteOrEmpty));

                Map<String, Object> emailResult = emailComposerService.send(EmailRequestDTO.builder()
                        .template(TemplateUtils.generateTemplate(EmailTemplate.ADMISSION_RESULT.name(), data))
                        .to(to)
                        .subject(decisionEmailSubject(newStatus))
                        .recipientType("APPLICATION")
                        .recipientId(id)
                        .data(data)
                        .templateName(EmailTemplate.ADMISSION_RESULT.name())
                        .build());

                String notificationStatus = nestedString(emailResult, "data", "status");
                boolean sent = "SENT".equalsIgnoreCase(notificationStatus);
                notification.put("sent", sent);
                notification.put("status", notificationStatus == null ? "UNKNOWN" : notificationStatus);
                notification.put("message", sent
                        ? "Correo enviado correctamente"
                        : "El servicio de notificaciones no confirmó el envío del correo");
            } catch (Exception e) {
                notification.put("status", "FAILED");
                notification.put("message", "No fue posible enviar el correo de notificación");
                log.warn("[final-decision] Notificación ADMISSION_RESULT no enviada para applicationId={}: {}", id, e.getMessage());
            }
        } else {
            log.warn("[final-decision] sin email destinatario para applicationId={}", id);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", switch (newStatus) {
            case APPROVED -> "Postulación aprobada";
            case WAITLIST -> "Postulación agregada a la lista de espera";
            case REJECTED -> "Postulación rechazada";
            default -> "Decisión registrada";
        });
        response.put("data", toFullResponse(saved));
        response.put("notification", notification);
        return response;
    }

    @Transactional
    public Map<String, Object> markDocumentNotificationSent(Long id) {
        ApplicationEntity entity = load(id);
        long totalDocuments = documentRepository.countByApplicationId(id);
        long approvedDocuments = documentRepository.countByApplicationIdAndApprovalStatus(id, DocumentApprovalStatus.APPROVED);
        boolean allApproved = totalDocuments > 0 && totalDocuments == approvedDocuments;
        entity.setDocumentosCompletos(allApproved);
        entity.setLastDocumentNotificationAt(LocalDateTime.now());
        applicationRepository.save(entity);
        return Map.of("success", true, "data", Map.of("id", id, "lastDocumentNotificationAt", entity.getLastDocumentNotificationAt(), "documentosCompletos", allApproved, "totalDocuments", totalDocuments, "approvedDocuments", approvedDocuments, "allDocsApproved", allApproved));
    }

    @Transactional
    public Map<String, Object> archive(Long id) {
        ApplicationEntity entity = load(id);
        entity.setArchived(true);
        entity.setStatus(ApplicationStatus.ARCHIVED);
        return Map.of("success", true, "message", "Postulación archivada", "data", toFullResponse(applicationRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        ApplicationEntity entity = load(id);
        entity.setDeletedAt(LocalDateTime.now());
        applicationRepository.save(entity);
        return Map.of("success", true, "message", "Postulación eliminada correctamente");
    }

    @Transactional
    public Map<String, Object> bulkUpdateStatus(Map<String, Object> payload) {
        List<?> ids = (List<?>) payload.getOrDefault("applicationIds", List.of());
        ApplicationStatus status = parseStatus(value(payload.get("status")));
        for (Object id : ids) {
            ApplicationEntity entity = load(((Number) id).longValue());
            entity.setStatus(status);
            entity.setNotes(value(payload.getOrDefault("notes", entity.getNotes())));
            applicationRepository.save(entity);
        }
        List<Map<String, Object>> data = ids.stream().map(item -> toFullResponse(load(((Number) item).longValue()))).toList();
        return Map.of("success", true, "message", "Estados actualizados", "data", data);
    }

    @Transactional
    public Map<String, Object> upsertComplementaryForm(Long applicationId, Map<String, Object> payload) {
        ApplicationEntity application = load(applicationId);
        if (application.isPaymentRequired() && application.getPaymentStatus() != PaymentStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Debe pagar la postulación antes de completar el formulario complementario");
        }
        ComplementaryFormEntity form = complementaryFormRepository.findByApplicationId(applicationId).orElseGet(() -> {
            ComplementaryFormEntity entity = new ComplementaryFormEntity();
            entity.setApplication(application);
            entity.setCreatedAt(LocalDateTime.now());
            return entity;
        });
        if (form.isSubmitted()) {
            throw new IllegalArgumentException("El formulario complementario ya fue enviado y no admite edición");
        }
        boolean submitted = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("isSubmitted", false)));
        form.setFormData(jsonSupport.write(payload));
        form.setSubmitted(submitted);
        form.setSubmittedAt(submitted ? LocalDateTime.now() : null);
        form.setUpdatedAt(LocalDateTime.now());
        return toComplementaryFormResponse(complementaryFormRepository.save(form));
    }

    public Map<String, Object> clearCache() {
        return Map.of("success", true, "message", "El monolito no usa caché distribuido para postulaciones");
    }

    public Map<String, Object> listDebug() {
        long total = applicationRepository.countByDeletedAtIsNull();
        long active = applicationRepository.findAllActive(PageRequest.of(0, 1)).getTotalElements();
        long archived = applicationRepository.countByDeletedAtIsNull() - active;

        return Map.of("success", true, "data", Map.of(
            "totalWithoutDeletedAt", total,
            "totalActive(notArchived)", active,
            "totalArchived", archived,
            "query", "Esto muestra si hay datos que están siendo excluidos por is_archived=true"
        ));
    }

    public Map<String, Object> systemInfo() {
        java.nio.file.Path path = java.nio.file.Path.of(uploadsDir).toAbsolutePath();
        return Map.of("success", true, "data", Map.of("uploadsDir", path.toString(), "exists", java.nio.file.Files.exists(path), "writable", java.nio.file.Files.isWritable(path)));
    }

    private ApplicationEntity load(Long id) {
        return applicationRepository.findActiveById(id).orElseThrow(() -> new IllegalArgumentException("Postulación no encontrada"));
    }

    private StudentEntity resolveStudent(Map<String, Object> payload) {
        if (payload.get("studentId") instanceof Number number) {
            return studentRepository.findById(number.longValue()).orElseThrow(() -> new IllegalArgumentException("Estudiante no encontrado"));
        }
        Object nested = payload.get("student");
        Map<String, Object> source = nested instanceof Map<?, ?> map ? (Map<String, Object>) map : payload;
        StudentEntity student = new StudentEntity();
        student.setFirstName(firstNonNull(source.get("firstName"), payload.get("firstName"), payload.get("studentFirstName")));
        student.setPaternalLastName(firstNonNull(source.get("paternalLastName"), payload.get("paternalLastName"), payload.get("studentPaternalLastName")));
        student.setMaternalLastName(firstNonNull(source.get("maternalLastName"), payload.get("maternalLastName"), payload.get("studentMaternalLastName")));
        String rut = firstNonNull(source.get("rut"), payload.get("rut"), payload.get("studentRUT"), payload.get("studentRut"));
        student.setRut(rut);

        if (rut != null) {
            var existingStudent = studentRepository.findByRut(rut);
            if (existingStudent.isPresent() && applicationRepository.existsByStudentIdAndDeletedAtIsNull(existingStudent.get().getId())) {
                throw new IllegalStateException("Ya existe una postulación activa para el RUT " + rut);
            }
        }

        student.setBirthDate(parseDate(firstNonNullRaw(source.get("birthDate"), payload.get("birthDate"), payload.get("studentDateOfBirth"), payload.get("studentBirthDate"))));
        student.setEmail(firstNonNull(source.get("email"), payload.get("studentEmail")));
        student.setAddress(firstNonNull(source.get("address"), payload.get("studentAddress")));
        student.setGradeApplied(firstNonNull(source.get("gradeApplied"), payload.get("grade"), payload.get("gradeApplied"), payload.get("gradeAppliedFor")));
        student.setGender(mapTargetSchoolToGender(firstNonNull(source.get("targetSchool"), payload.get("schoolApplied"), payload.get("targetSchool"), payload.get("studentAdmissionPreference"))));
        student.setCurrentSchool(firstNonNull(source.get("currentSchool"), payload.get("currentSchool"), payload.get("studentCurrentSchool")));
        student.setSpecialNeeds(booleanValue(source.getOrDefault("specialNeeds", false)));
        student.setSpecialNeedsDescription(value(source.get("specialNeedsDescription")));
        student.setAdditionalNotes(value(source.getOrDefault("additionalNotes", payload.get("additionalNotes"))));
        student.setEmployeeChild(booleanValue(source.getOrDefault("isEmployeeChild", false)));
        student.setEmployeeParentName(value(source.get("employeeParentName")));
        student.setAlumniChild(booleanValue(source.getOrDefault("isAlumniChild", false)));
        student.setAlumniParentYear(integerValue(source.get("alumniParentYear")));
        student.setInclusionStudent(booleanValue(source.getOrDefault("isInclusionStudent", false)));
        student.setInclusionType(value(source.get("inclusionType")));
        student.setInclusionNotes(value(source.get("inclusionNotes")));
        student.setHasSiblingsInSchool(booleanValue(source.getOrDefault("hasSiblingsInSchool", false)));
        student.setSiblingsInSchoolDetails(value(source.get("siblingsInSchoolDetails")));
        return studentRepository.save(student);
    }

    private ParentEntity resolveParent(Map<String, Object> payload, String nestedKey, String flatPrefix, String parentType, boolean required) {
        Object parents = payload.get("parents");
        Map<String, Object> nested = parents instanceof Map<?, ?> map && map.get(nestedKey) instanceof Map<?, ?> inner ? (Map<String, Object>) inner : null;
        if (payload.get(flatPrefix + "Name") == null && (nested == null || nested.get("fullName") == null) && !required) {
            return null;
        }
        if (payload.get(flatPrefix + "Id") instanceof Number id) {
            return parentRepository.findById(id.longValue()).orElseThrow(() -> new IllegalArgumentException("Pariente no encontrado"));
        }
        ParentEntity entity = new ParentEntity();
        entity.setFullName(value(nested != null ? nested.get("fullName") : payload.get(flatPrefix + "Name")));
        entity.setRut(value(nested != null ? nested.get("rut") : payload.get(flatPrefix + "Rut")));
        entity.setEmail(value(nested != null ? nested.get("email") : payload.get(flatPrefix + "Email")));
        entity.setPhone(value(nested != null ? nested.get("phone") : payload.get(flatPrefix + "Phone")));
        entity.setAddress(value(nested != null ? nested.get("address") : payload.get(flatPrefix + "Address")));
        entity.setProfession(value(nested != null ? nested.get("profession") : payload.get(flatPrefix + "Profession")));
        entity.setParentType(parentType);
        return parentRepository.save(entity);
    }

    private GuardianEntity resolveGuardian(Map<String, Object> payload) {
        if (payload.get("guardianId") instanceof Number id) {
            return guardianRepository.findById(id.longValue()).orElseThrow(() -> new IllegalArgumentException("Apoderado no encontrado"));
        }
        Object nested = payload.get("guardian");
        Map<String, Object> source = nested instanceof Map<?, ?> map ? (Map<String, Object>) map : payload;
        if (source.get("fullName") == null && payload.get("guardianName") == null) {
            return null;
        }
        GuardianEntity entity = new GuardianEntity();
        entity.setFullName(value(source.getOrDefault("fullName", payload.get("guardianName"))));
        entity.setRut(value(source.getOrDefault("rut", payload.get("guardianRut"))));
        entity.setEmail(value(source.getOrDefault("email", payload.get("guardianEmail"))));
        entity.setPhone(value(source.getOrDefault("phone", payload.get("guardianPhone"))));
        entity.setRelationship(value(source.getOrDefault("relationship", payload.get("guardianRelation"))));
        entity.setAddress(value(source.getOrDefault("address", payload.get("guardianAddress"))));
        return guardianRepository.save(entity);
    }

    private SupporterEntity resolveSupporter(Map<String, Object> payload) {
        if (payload.get("supporterId") instanceof Number id) {
            return supporterRepository.findById(id.longValue()).orElseThrow(() -> new IllegalArgumentException("Sostenedor no encontrado"));
        }
        Object nested = payload.get("supporter");
        Map<String, Object> source = nested instanceof Map<?, ?> map ? (Map<String, Object>) map : payload;
        if (source.get("fullName") == null && payload.get("supporterName") == null) {
            return null;
        }
        SupporterEntity entity = new SupporterEntity();
        entity.setFullName(value(source.getOrDefault("fullName", payload.get("supporterName"))));
        entity.setRut(value(source.getOrDefault("rut", payload.get("supporterRut"))));
        entity.setEmail(value(source.getOrDefault("email", payload.get("supporterEmail"))));
        entity.setPhone(value(source.getOrDefault("phone", payload.get("supporterPhone"))));
        entity.setRelationship(value(source.getOrDefault("relationship", payload.get("supporterRelation"))));
        entity.setAddress(value(source.getOrDefault("address", payload.get("supporterAddress"))));
        return supporterRepository.save(entity);
    }

    private UserEntity resolveApplicant(Map<String, Object> payload) {
        if (payload.get("applicantUserId") instanceof Number id) {
            return userRepository.findById(id.longValue()).orElseThrow(() -> new IllegalArgumentException("Usuario postulante no encontrado"));
        }
        return authService.requireAuthenticatedUser();
    }

    private Map<String, Object> toPublicResponse(ApplicationEntity entity) {
        return Map.of(
            "id", entity.getId(),
            "status", entity.getStatus().name(),
            "submission_date", entity.getSubmissionDate(),
            "created_at", entity.getCreatedAt(),
            "updated_at", entity.getUpdatedAt(),
            "student_rut", entity.getStudent().getRut(),
            "student_first_name", entity.getStudent().getFirstName(),
            "student_paternal_last_name", entity.getStudent().getPaternalLastName(),
            "student_maternal_last_name", entity.getStudent().getMaternalLastName()
        );
    }

    private Map<String, Object> toDataTableResponse(ApplicationEntity entity) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entity.getId());
        response.put("status", entity.getStatus().name());
        response.put("submissionDate", entity.getSubmissionDate());

        // Student data - completo
        Map<String, Object> studentMap = new LinkedHashMap<>();
        StudentEntity student = entity.getStudent();
        studentMap.put("id", student.getId());
        studentMap.put("firstName", student.getFirstName());
        studentMap.put("lastName", (value(student.getPaternalLastName()) + " " + value(student.getMaternalLastName())).trim());
        studentMap.put("paternalLastName", value(student.getPaternalLastName()));
        studentMap.put("maternalLastName", value(student.getMaternalLastName()));
        studentMap.put("rut", student.getRut());
        studentMap.put("birthDate", student.getBirthDate());
        studentMap.put("email", student.getEmail());
        studentMap.put("address", student.getAddress());
        studentMap.put("gradeApplied", student.getGradeApplied());
        studentMap.put("currentSchool", student.getCurrentSchool());
        studentMap.put("gender", student.getGender());
        studentMap.put("additionalNotes", student.getAdditionalNotes());
        studentMap.put("isEmployeeChild", student.isEmployeeChild());
        studentMap.put("employeeParentName", student.getEmployeeParentName());
        studentMap.put("isAlumniChild", student.isAlumniChild());
        studentMap.put("alumniParentYear", student.getAlumniParentYear());
        studentMap.put("isInclusionStudent", student.isInclusionStudent());
        studentMap.put("inclusionType", student.getInclusionType());
        studentMap.put("inclusionNotes", student.getInclusionNotes());
        studentMap.put("hasSiblingsInSchool", student.isHasSiblingsInSchool());
        studentMap.put("siblingsInSchoolDetails", student.getSiblingsInSchoolDetails());
        response.put("student", studentMap);

        // Father
        if (entity.getFather() != null) {
            Map<String, Object> fatherMap = new LinkedHashMap<>();
            fatherMap.put("fullName", entity.getFather().getFullName());
            fatherMap.put("rut", entity.getFather().getRut());
            fatherMap.put("email", value(entity.getFather().getEmail()));
            fatherMap.put("phone", value(entity.getFather().getPhone()));
            fatherMap.put("address", value(entity.getFather().getAddress()));
            fatherMap.put("profession", value(entity.getFather().getProfession()));
            response.put("father", fatherMap);
        }

        // Mother
        if (entity.getMother() != null) {
            Map<String, Object> motherMap = new LinkedHashMap<>();
            motherMap.put("fullName", entity.getMother().getFullName());
            motherMap.put("rut", entity.getMother().getRut());
            motherMap.put("email", value(entity.getMother().getEmail()));
            motherMap.put("phone", value(entity.getMother().getPhone()));
            motherMap.put("address", value(entity.getMother().getAddress()));
            motherMap.put("profession", value(entity.getMother().getProfession()));
            response.put("mother", motherMap);
        }

        // Guardian
        if (entity.getGuardian() != null) {
            Map<String, Object> guardianMap = new LinkedHashMap<>();
            guardianMap.put("fullName", entity.getGuardian().getFullName());
            guardianMap.put("email", value(entity.getGuardian().getEmail()));
            guardianMap.put("phone", value(entity.getGuardian().getPhone()));
            guardianMap.put("relationship", value(entity.getGuardian().getRelationship()));
            response.put("guardian", guardianMap);
        }

        // Documents - lista simplificada
        List<DocumentEntity> docs = documentRepository.findByApplicationIdOrderByUploadDateDesc(entity.getId());
        List<Map<String, Object>> documentsArray = docs.stream().map(d -> {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", d.getId());
            doc.put("type", d.getDocumentType());
            doc.put("status", d.getApprovalStatus().name());
            return doc;
        }).toList();
        response.put("documents", documentsArray);

        // Applicant user
        if (entity.getApplicantUser() != null) {
            response.put("applicantUser", Map.of("email", entity.getApplicantUser().getEmail()));
        }

        return response;
    }

    private Map<String, Object> toSummaryResponse(ApplicationEntity entity) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entity.getId());
        response.put("studentId", entity.getStudent().getId());
        response.put("fatherId", entity.getFather() == null ? null : entity.getFather().getId());
        response.put("motherId", entity.getMother() == null ? null : entity.getMother().getId());
        response.put("supporterId", entity.getSupporter() == null ? null : entity.getSupporter().getId());
        response.put("guardianId", entity.getGuardian() == null ? null : entity.getGuardian().getId());
        response.put("applicantUserId", entity.getApplicantUser() == null ? null : entity.getApplicantUser().getId());
        response.put("status", entity.getStatus().name());
        response.put("paymentStatus", entity.getPaymentStatus().name());
        response.put("paymentRequired", entity.isPaymentRequired());
        response.put("paidAt", entity.getPaidAt());
        response.put("canFillComplementaryForm", !entity.isPaymentRequired() || entity.getPaymentStatus() == PaymentStatus.PAID);
        response.put("hasComplementaryForm", complementaryFormRepository.existsByApplicationIdAndSubmittedTrue(entity.getId()));
        response.put("submissionDate", entity.getSubmissionDate());
        response.put("createdAt", entity.getCreatedAt());
        response.put("updatedAt", entity.getUpdatedAt());
        Map<String, Object> studentMap = new LinkedHashMap<>();
        studentMap.put("id", entity.getStudent().getId());
        studentMap.put("firstName", entity.getStudent().getFirstName());
        studentMap.put("paternalLastName", value(entity.getStudent().getPaternalLastName()));
        studentMap.put("maternalLastName", value(entity.getStudent().getMaternalLastName()));
        studentMap.put("lastName", (value(entity.getStudent().getPaternalLastName()) + " " + value(entity.getStudent().getMaternalLastName())).trim());
        studentMap.put("rut", entity.getStudent().getRut());
        studentMap.put("birthDate", entity.getStudent().getBirthDate());
        studentMap.put("gradeApplying", entity.getStudent().getGradeApplied());
        studentMap.put("gradeApplied", entity.getStudent().getGradeApplied());
        studentMap.put("grade", entity.getStudent().getGradeApplied());
        studentMap.put("currentSchool", entity.getStudent().getCurrentSchool());
        studentMap.put("address", entity.getStudent().getAddress());
        studentMap.put("email", entity.getStudent().getEmail());
        studentMap.put("additionalNotes", entity.getStudent().getAdditionalNotes());
        studentMap.put("pais", entity.getStudent().getPais());
        studentMap.put("region", entity.getStudent().getRegion());
        studentMap.put("comuna", entity.getStudent().getComuna());
        studentMap.put("admissionPreference", entity.getStudent().getAdmissionPreference());
        studentMap.put("specialNeeds", entity.getStudent().isSpecialNeeds());
        studentMap.put("specialNeedsDescription", entity.getStudent().getSpecialNeedsDescription());
        studentMap.put("gender", entity.getStudent().getGender());
        studentMap.put("hasSiblingsInSchool", entity.getStudent().isHasSiblingsInSchool());
        studentMap.put("siblingsInSchoolDetails", entity.getStudent().getSiblingsInSchoolDetails());
        response.put("student", studentMap);
        if (entity.getGuardian() != null) {
            Map<String, Object> guardianMap = new LinkedHashMap<>();
            guardianMap.put("id", entity.getGuardian().getId());
            guardianMap.put("fullName", entity.getGuardian().getFullName());
            guardianMap.put("rut", entity.getGuardian().getRut());
            guardianMap.put("email", value(entity.getGuardian().getEmail()));
            guardianMap.put("phone", value(entity.getGuardian().getPhone()));
            guardianMap.put("relationship", value(entity.getGuardian().getRelationship()));
            response.put("guardian", guardianMap);
        } else {
            response.put("guardian", null);
        }
        return response;
    }

    private Map<String, Object> toFullResponse(ApplicationEntity entity) {
        Map<String, Object> response = new LinkedHashMap<>(toSummaryResponse(entity));
        response.put("father", toParentResponse(entity.getFather(), "FATHER"));
        response.put("mother", toParentResponse(entity.getMother(), "MOTHER"));
        response.put("guardian", entity.getGuardian() == null ? null : Map.of("id", entity.getGuardian().getId(), "fullName", entity.getGuardian().getFullName(), "rut", entity.getGuardian().getRut(), "email", entity.getGuardian().getEmail(), "phone", entity.getGuardian().getPhone(), "relationship", entity.getGuardian().getRelationship(), "address", entity.getGuardian().getAddress()));
        response.put("supporter", entity.getSupporter() == null ? null : Map.of("id", entity.getSupporter().getId(), "fullName", entity.getSupporter().getFullName(), "rut", entity.getSupporter().getRut(), "email", entity.getSupporter().getEmail(), "phone", entity.getSupporter().getPhone(), "relationship", entity.getSupporter().getRelationship()));
        response.put("documents", documents(entity.getId()).get("data"));
        response.put("evaluations", evaluationRepository.findByApplicationIdOrderByCreatedAtDesc(entity.getId()).stream().map(evaluation -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", evaluation.getId());
            item.put("applicationId", entity.getId());
            item.put("evaluatorId", evaluation.getEvaluator() == null ? null : evaluation.getEvaluator().getId());
            item.put("type", evaluation.getEvaluationType());
            item.put("subject", evaluation.getSubject());
            item.put("status", evaluation.getStatus().name());
            item.put("score", evaluation.getScore());
            item.put("maxScore", evaluation.getMaxScore());
            item.put("evaluationDate", evaluation.getEvaluationDate());
            item.put("recommendations", evaluation.getRecommendations());
            item.put("observations", evaluation.getObservations());
            return item;
        }).toList());
        response.put("interviews", interviewRepository.findByApplicationIdOrderByScheduledDateDesc(entity.getId()).stream().map(interview -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", interview.getId());
            item.put("applicationId", entity.getId());
            item.put("interviewerId", interview.getInterviewer() == null ? null : interview.getInterviewer().getId());
            item.put("secondInterviewerId", interview.getSecondInterviewer() == null ? null : interview.getSecondInterviewer().getId());
            item.put("interviewType", interview.getInterviewType());
            item.put("scheduledDate", interview.getScheduledDate());
            item.put("scheduledTime", interview.getScheduledTime());
            item.put("duration", interview.getDuration());
            item.put("location", interview.getLocation());
            item.put("mode", interview.getMode());
            item.put("status", interview.getStatus().name());
            item.put("notes", interview.getNotes());
            return item;
        }).toList());
        return response;
    }

    private Map<String, Object> toParentResponse(ParentEntity entity, String relationship) {
        if (entity == null) return null;
        return Map.of("id", entity.getId(), "fullName", entity.getFullName(), "rut", entity.getRut(), "email", entity.getEmail(), "phone", entity.getPhone(), "address", entity.getAddress(), "occupation", entity.getProfession(), "relationship", relationship);
    }

    private Map<String, Object> toDocumentResponse(DocumentEntity document) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", document.getId());
        response.put("documentType", document.getDocumentType());
        response.put("fileName", document.getFileName());
        response.put("originalName", document.getOriginalName());
        response.put("filePath", document.getFilePath());
        response.put("fileSize", document.getFileSize());
        response.put("contentType", document.getContentType());
        response.put("isRequired", document.isRequired());
        response.put("approvalStatus", document.getApprovalStatus().name());
        response.put("rejectionReason", document.getRejectionReason());
        response.put("approvedBy", document.getApprovedBy() == null ? null : document.getApprovedBy().getId());
        response.put("approvalDate", document.getApprovalDate());
        response.put("uploadDate", document.getUploadDate());
        response.put("updatedAt", document.getUpdatedAt());
        return response;
    }

    private Map<String, Object> toComplementaryFormResponse(ComplementaryFormEntity form) {
        Map<String, Object> data = new LinkedHashMap<>(jsonSupport.readMap(form.getFormData()));
        data.put("id", form.getId());
        data.put("applicationId", form.getApplication().getId());
        data.put("isSubmitted", form.isSubmitted());
        data.put("submittedAt", form.getSubmittedAt());
        data.put("createdAt", form.getCreatedAt());
        data.put("updatedAt", form.getUpdatedAt());
        return Map.of("success", true, "data", data);
    }

    private Map<String, Object> pageResponse(Page<Map<String, Object>> page) {
        return Map.of("content", page.getContent(), "number", page.getNumber(), "size", page.getSize(), "totalElements", page.getTotalElements(), "totalPages", page.getTotalPages(), "first", page.isFirst(), "last", page.isLast(), "numberOfElements", page.getNumberOfElements(), "empty", page.isEmpty());
    }

    private ApplicationStatus parseStatus(String value) {
        return value == null || value.isBlank() ? null : ApplicationStatus.valueOf(value.toUpperCase());
    }

    private String fullStudentName(StudentEntity student) {
        return (value(student.getFirstName()) + " " + value(student.getPaternalLastName()) + " " + value(student.getMaternalLastName())).trim();
    }

    private String value(Object value) { return value == null ? "" : String.valueOf(value); }
    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private String firstNonNull(Object... values) {
        for (Object v : values) {
            if (v == null) continue;
            String s = String.valueOf(v);
            if (!s.isBlank()) return s;
        }
        return "";
    }

    private Object firstNonNullRaw(Object... values) {
        for (Object v : values) {
            if (v == null) continue;
            if (v instanceof String s && s.isBlank()) continue;
            return v;
        }
        return null;
    }
    private boolean booleanValue(Object value) { return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value)); }
    private Integer integerValue(Object value) { return value == null || String.valueOf(value).isBlank() ? null : value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value)); }
    private LocalDate parseDate(Object value) { return value == null || String.valueOf(value).isBlank() ? null : value instanceof LocalDate date ? date : LocalDate.parse(String.valueOf(value)); }

    private String mapTargetSchoolToGender(String targetSchool) {
        if (targetSchool == null) return null;
        return switch (targetSchool) {
            case "MONTE_TABOR" -> "MALE";
            case "NAZARET" -> "FEMALE";
            default -> null;
        };
    }

    private static String safe(String value) { return value == null ? "" : value; }

    /**
     * Arma un saludo familiar legible. Si un registro de padre/madre está incompleto
     * (por ejemplo, contiene sólo "A"), intenta recuperar el nombre desde el apoderado
     * o desde la cuenta que creó la postulación.
     */
    private static String resolveFamilyNamesForEmail(ApplicationEntity app) {
        java.util.List<String> names = new java.util.ArrayList<>();
        addFamilyName(names, app.getFather() == null ? null : app.getFather().getFullName());
        addFamilyName(names, app.getMother() == null ? null : app.getMother().getFullName());
        if (names.size() < 2) {
            addFamilyName(names, app.getGuardian() == null ? null : app.getGuardian().getFullName());
        }
        if (names.size() < 2 && app.getGuardian() != null && app.getGuardian().getUser() != null) {
            addFamilyName(names, (safe(app.getGuardian().getUser().getFirstName()) + " "
                    + safe(app.getGuardian().getUser().getLastName())).trim());
        }
        if (names.size() < 2 && app.getApplicantUser() != null) {
            addFamilyName(names, (safe(app.getApplicantUser().getFirstName()) + " "
                    + safe(app.getApplicantUser().getLastName())).trim());
        }
        return String.join(" y ", names);
    }

    private static void addFamilyName(java.util.List<String> names, String rawName) {
        String name = personNameForEmail(rawName);
        if (name == null) return;
        boolean duplicate = names.stream().anyMatch(existing -> existing.equalsIgnoreCase(name));
        if (!duplicate) names.add(name);
    }

    private static String personNameForEmail(String rawName) {
        if (rawName == null) return null;
        String cleaned = rawName.trim().replaceAll("\\s+", " ");
        String normalized = cleaned.toUpperCase(java.util.Locale.ROOT)
                .replace(".", "")
                .replace("/", "")
                .trim();
        if (cleaned.length() < 2
                || normalized.equals("A")
                || normalized.equals("NA")
                || normalized.equals("N A")
                || normalized.equals("NO APLICA")
                || normalized.equals("SIN INFORMACION")
                || normalized.equals("SIN REGISTRO")) {
            return null;
        }

        java.util.Set<String> connectors = java.util.Set.of("de", "del", "la", "las", "los", "y");
        String[] words = cleaned.toLowerCase(java.util.Locale.forLanguageTag("es-CL")).split(" ");
        for (int i = 0; i < words.length; i++) {
            if (i > 0 && connectors.contains(words[i])) continue;
            words[i] = Character.toUpperCase(words[i].charAt(0)) + words[i].substring(1);
        }
        return String.join(" ", words);
    }

    /** Prioriza la cuenta que creó la postulación y usa los contactos familiares como respaldo. */
    private static String resolveApplicationRecipientEmail(ApplicationEntity app) {
        String[] candidates = {
            app.getApplicantUser() == null ? null : app.getApplicantUser().getEmail(),
            app.getGuardian() == null ? null : app.getGuardian().getEmail(),
            app.getFather() == null ? null : app.getFather().getEmail(),
            app.getMother() == null ? null : app.getMother().getEmail()
        };
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) return candidate.trim();
        }
        return null;
    }

    private static String applicationStatusLabel(ApplicationStatus status) {
        if (status == null) return "Sin estado";
        return switch (status) {
            case PENDING -> "Pendiente";
            case UNDER_REVIEW -> "En revisión";
            case DOCUMENTS_REQUESTED, PENDING_DOCUMENTS -> "Documentos pendientes";
            case INCOMPLETE -> "Incompleta";
            case INTERVIEW_SCHEDULED -> "Entrevista agendada";
            case EXAM_SCHEDULED -> "Evaluación agendada";
            case APPROVED -> "Aprobada";
            case REJECTED -> "Rechazada";
            case WAITLIST -> "Lista de espera";
            case ARCHIVED -> "Archivada";
        };
    }

    private static String defaultDecisionMessage(ApplicationStatus status) {
        return switch (status) {
            case APPROVED -> "La postulación ha sido aprobada. Pronto recibirán información sobre los siguientes pasos.";
            case WAITLIST -> "La postulación ha sido incorporada a la lista de espera. Les contactaremos cuando exista una novedad sobre el cupo.";
            case REJECTED -> "El proceso de postulación ha finalizado. Agradecemos sinceramente el interés y la confianza en nuestro colegio.";
            default -> "El estado de la postulación ha sido actualizado.";
        };
    }

    private static String decisionEmailSubject(ApplicationStatus status) {
        return switch (status) {
            case APPROVED -> "Resultado de admisión: postulación aprobada";
            case WAITLIST -> "Resultado de admisión: lista de espera";
            case REJECTED -> "Resultado del proceso de admisión";
            default -> EmailTemplate.ADMISSION_RESULT.getDefaultSubject();
        };
    }

    private static String decisionResultBackground(ApplicationStatus status) {
        return switch (status) {
            case APPROVED -> "#ecfdf5";
            case WAITLIST -> "#fffbeb";
            case REJECTED -> "#fef2f2";
            default -> "#f8fafc";
        };
    }

    private static String decisionResultColor(ApplicationStatus status) {
        return switch (status) {
            case APPROVED -> "#047857";
            case WAITLIST -> "#92400e";
            case REJECTED -> "#b91c1c";
            default -> "#273b7a";
        };
    }

    private static String decisionResultBorder(ApplicationStatus status) {
        return switch (status) {
            case APPROVED -> "#10b981";
            case WAITLIST -> "#f59e0b";
            case REJECTED -> "#ef4444";
            default -> "#cbd5e1";
        };
    }

    private static String formatEmailMessage(String value) {
        return escapeHtml(value).replace("\r\n", "<br/>").replace("\n", "<br/>");
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String nestedString(Map<String, Object> source, String parentKey, String childKey) {
        if (source == null || !(source.get(parentKey) instanceof Map<?, ?> nested)) return null;
        Object value = nested.get(childKey);
        return value == null ? null : String.valueOf(value);
    }
}
