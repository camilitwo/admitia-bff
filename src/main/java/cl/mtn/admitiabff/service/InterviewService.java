package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.evaluation.EvaluationEntity;
import cl.mtn.admitiabff.domain.interview.InterviewEntity;
import cl.mtn.admitiabff.domain.interview.InterviewerScheduleEntity;
import cl.mtn.admitiabff.domain.interview.InterviewerPairEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.InterviewerScheduleRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import cl.mtn.admitiabff.util.TemplateUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InterviewService {
    private final InterviewRepository interviewRepository;
    private final InterviewerScheduleRepository scheduleRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final EvaluationRepository evaluationRepository;
    private final cl.mtn.admitiabff.service.notification.EmailComposerService emailComposerService;
    private final InterviewConfirmationService confirmationService;
    private final InterviewerPairService interviewerPairService;
    private final AdmissionCycleGuard admissionCycleGuard;

    public InterviewService(InterviewRepository interviewRepository, InterviewerScheduleRepository scheduleRepository, ApplicationRepository applicationRepository, UserRepository userRepository, EvaluationRepository evaluationRepository, cl.mtn.admitiabff.service.notification.EmailComposerService emailComposerService, InterviewConfirmationService confirmationService, InterviewerPairService interviewerPairService, AdmissionCycleGuard admissionCycleGuard) {
        this.interviewRepository = interviewRepository;
        this.scheduleRepository = scheduleRepository;
        this.applicationRepository = applicationRepository;
        this.userRepository = userRepository;
        this.evaluationRepository = evaluationRepository;
        this.emailComposerService = emailComposerService;
        this.confirmationService = confirmationService;
        this.interviewerPairService = interviewerPairService;
        this.admissionCycleGuard = admissionCycleGuard;
    }

    public List<Map<String, Object>> publicInterviewers() {
        return scheduleRepository.findPublicInterviewers().stream()
            .map(item -> {
                Map<String, Object> interviewer = new LinkedHashMap<>();
                interviewer.put("id", item.getInterviewerId());
                interviewer.put("name", item.getName());
                interviewer.put("role", String.valueOf(item.getRole()));
                interviewer.put("subject", item.getSubject());
                interviewer.put("educationalLevel", item.getEducationalLevel());
                interviewer.put("scheduleCount", item.getScheduleCount());
                return interviewer;
            })
            .toList();
    }

    public Map<String, Object> all() { return wrap(interviewRepository.findAllByOrderByCreatedAtDesc()); }

    public Map<String, Object> statistics() {
        long total = interviewRepository.count();
        long scheduled = interviewRepository.countByStatus(InterviewStatus.SCHEDULED);
        long completed = interviewRepository.countByStatus(InterviewStatus.COMPLETED);
        long cancelled = interviewRepository.countByStatus(InterviewStatus.CANCELLED);
        long upcoming = interviewRepository.countByScheduledDateGreaterThanEqualAndStatus(LocalDate.now(), InterviewStatus.SCHEDULED);
        Map<String, Object> byStatus = interviewRepository.countByStatus().stream().collect(java.util.stream.Collectors.toMap(InterviewRepository.KeyCountView::getKey, InterviewRepository.KeyCountView::getTotal, (a, b) -> b, LinkedHashMap::new));
        Map<String, Object> byType = interviewRepository.countByType().stream().collect(java.util.stream.Collectors.toMap(InterviewRepository.KeyCountView::getKey, InterviewRepository.KeyCountView::getTotal, (a, b) -> b, LinkedHashMap::new));
        List<Map<String, Object>> upcomingItems = interviewRepository.findForCalendar(LocalDate.now(), null).stream().limit(10).map(this::toResponse).toList();
        return Map.of("success", true, "data", Map.of("overview", Map.of("total", total, "scheduled", scheduled, "completed", completed, "cancelled", cancelled, "upcoming", upcoming, "completionRate", total == 0 ? 0 : (completed * 100.0) / total, "cancellationRate", total == 0 ? 0 : (cancelled * 100.0) / total), "byStatus", byStatus, "byType", byType, "upcoming", upcomingItems));
    }

    public Map<String, Object> calendar(String startDate, String endDate, boolean includeRejected) {
        LocalDate start = startDate == null || startDate.isBlank() ? null : LocalDate.parse(startDate);
        LocalDate end = endDate == null || endDate.isBlank() ? null : LocalDate.parse(endDate);
        List<Map<String, Object>> data = interviewRepository.findForCalendar(start, end, includeRejected).stream().map(this::toCalendarResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    public Map<String, Object> byApplication(Long applicationId) { return wrap(interviewRepository.findByApplicationIdOrderByScheduledDateDesc(applicationId)); }
    public Map<String, Object> summaryStatus(Long applicationId) { return Map.of("success", true, "data", Map.of("applicationId", applicationId, "summarySent", interviewRepository.countByApplicationIdAndSummarySentTrue(applicationId) > 0)); }
    public Map<String, Object> byInterviewer(Long interviewerId) {
        List<Map<String, Object>> data = interviewRepository.findVisibleForInterviewer(interviewerId, List.of(InterviewStatus.CANCELLED, InterviewStatus.RESCHEDULED)).stream().map(this::toResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    /**
     * Devuelve todos los entrevistadores disponibles para un slot específico (fecha + hora + duración).
     * Útil para mostrar parejas adicionales en slots ya agendados.
     */
    public Map<String, Object> slotAvailability(String dateStr, String timeStr, Integer duration) {
        LocalDate date = LocalDate.parse(dateStr);
        LocalTime time = LocalTime.parse(timeStr);
        int interviewDuration = duration == null ? 60 : duration;
        LocalTime slotEnd = time.plusMinutes(interviewDuration);

        List<Map<String, Object>> freeInterviewers = scheduleRepository.findInterviewersWithSchedules(date.getYear()).stream()
            .filter(interviewer -> {
                List<InterviewerScheduleEntity> schedules = scheduleRepository.findAvailableTemplates(
                    interviewer.getInterviewerId(), date, dayName(date), date.getYear()
                );
                boolean coveredBySchedule = schedules.stream()
                    .anyMatch(s -> !time.isBefore(s.getStartTime()) && !slotEnd.isAfter(s.getEndTime()));
                if (!coveredBySchedule) return false;
                List<InterviewEntity> booked = interviewRepository.findBlockingForInterviewer(
                    interviewer.getInterviewerId(), date,
                    List.of(InterviewStatus.CANCELLED, InterviewStatus.RESCHEDULED, InterviewStatus.REJECTED_BY_FAMILY)
                );
                return booked.stream().noneMatch(interview ->
                    overlaps(time, slotEnd, interview.getScheduledTime(),
                        interview.getScheduledTime().plusMinutes(interview.getDuration() == null ? 60 : interview.getDuration()))
                );
            })
            .map(interviewer -> Map.<String, Object>of(
                "id", interviewer.getInterviewerId(),
                "name", (interviewer.getFirstName() + " " + interviewer.getLastName()).trim(),
                "role", String.valueOf(interviewer.getRole())
            ))
            .toList();

        List<Map<String, Object>> availablePairs = interviewerPairService.availablePairs(date, time, interviewDuration);
        int familyPairCount = countFamilyPairs(freeInterviewers);
        List<String> availableInterviewTypes = availableInterviewTypes(familyPairCount, availablePairs.size());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", dateStr);
        data.put("time", timeStr);
        data.put("duration", interviewDuration);
        data.put("availableInterviewers", freeInterviewers);
        data.put("interviewerCount", freeInterviewers.size());
        data.put("availablePairs", availablePairs);
        data.put("availablePairCount", availablePairs.size());
        data.put("familyPairCount", familyPairCount);
        data.put("availableInterviewTypes", availableInterviewTypes);
        return Map.of("success", true, "data", data);
    }

    public Map<String, Object> availableSlots(Long interviewerId, String date, Integer duration) {
        LocalDate targetDate = LocalDate.parse(date);
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        int interviewDuration = duration == null ? 60 : duration;
        List<InterviewerScheduleEntity> schedules = scheduleRepository.findAvailableTemplates(interviewerId, targetDate, dayName(targetDate), targetDate.getYear());
        List<InterviewEntity> booked = interviewRepository.findBlockingForInterviewer(interviewerId, targetDate,
                List.of(InterviewStatus.CANCELLED, InterviewStatus.RESCHEDULED, InterviewStatus.REJECTED_BY_FAMILY));
        List<Map<String, Object>> slots = new ArrayList<>();
        for (InterviewerScheduleEntity schedule : schedules) {
            LocalTime current = schedule.getStartTime();
            while (current.isBefore(schedule.getEndTime())) {
                LocalTime slot = current;
                // Skip slots in the past for today's date
                if (targetDate.equals(today) && slot.isBefore(now)) {
                    current = current.plusMinutes(30);
                    continue;
                }
                LocalTime slotEnd = slot.plusMinutes(interviewDuration);
                boolean canFitDuration = !slotEnd.isAfter(schedule.getEndTime());
                boolean occupied = booked.stream().anyMatch(interview -> overlaps(slot, slotEnd, interview.getScheduledTime(), interview.getScheduledTime().plusMinutes(interview.getDuration() == null ? 60 : interview.getDuration())));
                if (canFitDuration && !occupied) {
                    slots.add(Map.of("time", slot.toString(), "display", slot.toString(), "canFitDuration", true));
                }
                current = current.plusMinutes(30);
            }
        }
        return Map.of("success", true, "data", Map.of("availableSlots", slots, "date", date, "interviewerId", interviewerId, "duration", interviewDuration));
    }

    public Map<String, Object> nextAvailableSlots(String date, Integer days, Integer duration) {
        LocalDate startDate = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        int daysToSearch = Math.max(1, Math.min(days == null ? 5 : days, 14));
        int interviewDuration = Math.max(15, Math.min(duration == null ? 30 : duration, 240));
        LocalDate endDate = startDate.plusDays(daysToSearch - 1L);

        List<Map<String, Object>> slotsByDate = new ArrayList<>();
        Map<String, Object> nextAvailable = null;

        for (int offset = 0; offset < daysToSearch; offset++) {
            LocalDate targetDate = startDate.plusDays(offset);
            Map<LocalTime, Map<Long, Map<String, Object>>> availabilityByTime = new HashMap<>();

            scheduleRepository.findInterviewersWithSchedules(targetDate.getYear()).forEach(interviewer -> {
                List<InterviewerScheduleEntity> schedules = scheduleRepository.findAvailableTemplates(
                    interviewer.getInterviewerId(),
                    targetDate,
                    dayName(targetDate),
                    targetDate.getYear()
                );
                List<InterviewEntity> booked = interviewRepository.findBlockingForInterviewer(
                    interviewer.getInterviewerId(),
                    targetDate,
                    List.of(InterviewStatus.CANCELLED, InterviewStatus.RESCHEDULED, InterviewStatus.REJECTED_BY_FAMILY)
                );

                for (InterviewerScheduleEntity schedule : schedules) {
                    LocalTime current = schedule.getStartTime();
                    while (!current.plusMinutes(interviewDuration).isAfter(schedule.getEndTime())) {
                        LocalTime slot = current;
                        // Skip slots in the past for today's date
                        if (targetDate.equals(today) && slot.isBefore(now)) {
                            current = current.plusMinutes(30);
                            continue;
                        }
                        LocalTime slotEnd = slot.plusMinutes(interviewDuration);
                        boolean occupied = booked.stream().anyMatch(interview ->
                            overlaps(slot, slotEnd, interview.getScheduledTime(), interview.getScheduledTime().plusMinutes(interview.getDuration() == null ? 60 : interview.getDuration()))
                        );
                        if (!occupied) {
                            availabilityByTime
                                .computeIfAbsent(slot, ignored -> new LinkedHashMap<>())
                                .putIfAbsent(interviewer.getInterviewerId(), interviewerInfo(interviewer));
                        }
                        current = current.plusMinutes(30);
                    }
                }
            });

            List<Map<String, Object>> daySlots = availabilityByTime.entrySet().stream()
                .filter(entry -> countFamilyPairs(new ArrayList<>(entry.getValue().values())) > 0)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    List<Map<String, Object>> availableInterviewers = new ArrayList<>(entry.getValue().values());
                    Map<String, Object> suggestedPair = pickBestFamilyPair(availableInterviewers);

                    Map<String, Object> slot = new LinkedHashMap<>();
                    slot.put("time", entry.getKey().toString());
                    slot.put("availableInterviewers", availableInterviewers);
                    slot.put("interviewerCount", availableInterviewers.size());
                    slot.put("suggestedPair", suggestedPair);
                    return slot;
                })
                .toList();

            if (nextAvailable == null && !daySlots.isEmpty()) {
                Map<String, Object> firstSlot = daySlots.get(0);
                Map<String, Object> next = new LinkedHashMap<>();
                next.put("date", targetDate.toString());
                next.put("time", firstSlot.get("time"));
                next.put("dayOfWeek", targetDate.getDayOfWeek().name());
                next.put("interviewers", List.of(
                    ((Map<?, ?>) firstSlot.get("suggestedPair")).get("interviewer1"),
                    ((Map<?, ?>) firstSlot.get("suggestedPair")).get("interviewer2")
                ));
                nextAvailable = next;
            }

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", targetDate.toString());
            day.put("dayOfWeek", targetDate.getDayOfWeek().name());
            day.put("dayLabel", formatDayLabel(targetDate));
            day.put("slots", daySlots);
            slotsByDate.add(day);
        }

        Map<String, Object> searchRange = new LinkedHashMap<>();
        searchRange.put("from", startDate.toString());
        searchRange.put("to", endDate.toString());
        searchRange.put("daysSearched", daysToSearch);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("searchRange", searchRange);
        data.put("nextAvailable", nextAvailable);
        data.put("slotsByDate", slotsByDate);
        data.put("duration", interviewDuration);
        return Map.of("success", true, "data", data);
    }

    public Map<String, Object> get(Long id) { return toResponse(load(id)); }

    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        InterviewEntity entity = new InterviewEntity();
        merge(entity, payload);
        guardMutation(entity);
        boolean pairApplied = applyInterviewerPair(entity, payload, null);
        validateInterviewerComposition(entity);

        // Validar que no exista una entrevista activa del mismo tipo para esta postulación
        Long applicationId = entity.getApplication() != null ? entity.getApplication().getId() : null;
        String interviewType = entity.getInterviewType();
        if (applicationId != null && interviewType != null) {
            ensureNoDuplicateInterview(applicationId, interviewType, null);
        }

        if (!pairApplied) ensureInterviewersAvailable(entity);
        InterviewEntity saved = interviewRepository.save(entity);

        // Crear automáticamente la evaluación para entrevistas familiares y de director
        createEvaluationForInterview(saved);

        return Map.of("success", true, "message", "Entrevista creada correctamente", "data", toResponse(saved));
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> payload) {
        InterviewEntity entity = load(id);
        guardMutation(entity);
        merge(entity, payload);
        guardMutation(entity);
        boolean pairApplied = applyInterviewerPair(entity, payload, id);
        validateInterviewerComposition(entity);
        if (!pairApplied) ensureInterviewersAvailable(entity);
        return Map.of("success", true, "message", "Entrevista actualizada", "data", toResponse(interviewRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        InterviewEntity entity = load(id);
        guardMutation(entity);
        interviewRepository.delete(entity);
        return Map.of("success", true, "message", "Entrevista eliminada correctamente");
    }

    @Transactional
    public Map<String, Object> cancel(Long id, Map<String, Object> payload) {
        InterviewEntity entity = load(id);
        guardMutation(entity);
        entity.setStatus(InterviewStatus.CANCELLED);
        if (payload != null && payload.get("reason") != null) entity.setNotes(String.valueOf(payload.get("reason")));
        return Map.of("success", true, "message", "Entrevista cancelada", "data", toResponse(interviewRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> reschedule(Long id, Map<String, Object> payload) {
        InterviewEntity entity = load(id);
        guardMutation(entity);
        entity.setScheduledDate(LocalDate.parse(String.valueOf(payload.get("scheduledDate"))));
        entity.setScheduledTime(LocalTime.parse(String.valueOf(payload.get("scheduledTime"))));
        entity.setStatus(InterviewStatus.RESCHEDULED);
        entity.setNotes(payload.get("notes") == null ? entity.getNotes() : String.valueOf(payload.get("notes")));
        boolean pairApplied = applyInterviewerPair(entity, payload, id);
        validateInterviewerComposition(entity);
        if (!pairApplied) ensureInterviewersAvailable(entity);
        return Map.of("success", true, "message", "Entrevista reprogramada", "data", toResponse(interviewRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> release(Long id, Map<String, Object> payload) {
        InterviewEntity entity = load(id);
        guardMutation(entity);
        // Solo se pueden liberar entrevistas rechazadas por la familia
        if (entity.getStatus() != InterviewStatus.REJECTED_BY_FAMILY) {
            throw new IllegalStateException("Solo se pueden liberar entrevistas rechazadas por la familia");
        }
        entity.setStatus(InterviewStatus.CANCELLED);
        String reason = payload != null && payload.get("reason") != null 
            ? String.valueOf(payload.get("reason")) 
            : "Liberada por administrador después de rechazo de familia";
        entity.setNotes(reason);
        return Map.of("success", true, "message", "Entrevista liberada - ahora puede reprogramarse", "data", toResponse(interviewRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> sendSummary(Long applicationId) {
        var application = applicationRepository.findActiveById(applicationId)
                .orElseThrow(() -> new IllegalStateException(
                        "No se puede enviar el resumen: la postulación " + applicationId + " no existe."));
        admissionCycleGuard.assertOpen(application);
        List<InterviewEntity> interviews = interviewRepository.findByApplicationIdOrderByScheduledDateDesc(applicationId);
        interviews.forEach(interview -> interview.setSummarySent(true));
        interviewRepository.saveAll(interviews);

        // Destinatario: SIEMPRE desde la base de datos (applicantUser de la postulación).
        // Si no hay correo válido se aborta el envío con error explícito (nunca hardcodear).
        String to = application.getApplicantUser() != null ? application.getApplicantUser().getEmail() : null;
        if (to == null || to.isBlank()) {
            throw new IllegalStateException(
                    "No se puede enviar el resumen: la postulación " + applicationId
                            + " no tiene un email de destinatario válido (applicantUser.email).");
        }

        // Datos del alumno y apoderados desde el primer registro disponible.
        String studentName = interviews.stream()
                .map(this::toResponse)
                .map(r -> (String) r.get("studentName"))
                .filter(s -> s != null && !s.isBlank())
                .findFirst().orElse("(sin nombre)");
        String parentNames = interviews.stream()
                .map(this::toResponse)
                .map(r -> (String) r.get("parentNames"))
                .filter(s -> s != null && !s.isBlank())
                .findFirst().orElse("");
        String parentNamesFriendly = toFriendlyParentNames(parentNames);
        String gradeApplied = interviews.stream()
                .map(this::toResponse)
                .map(r -> (String) r.get("gradeApplied"))
                .filter(s -> s != null && !s.isBlank())
                .findFirst().orElse("");

        // Lista para el {{#each interviews}} del template.
        List<Map<String, Object>> interviewRows = interviews.stream().map(i -> {
            Map<String, Object> row = toResponse(i);
            String second = (String) row.get("secondInterviewerName");
            row.put("secondInterviewerSuffix", (second == null || second.isBlank()) ? "" : " / " + toTitleCase(second));
            row.put("interviewerName", toTitleCase((String) row.get("interviewerName")));
            row.put("interviewType", prettyInterviewType((String) row.get("interviewType")));
            row.put("mode", prettyMode((String) row.get("mode")));
            return row;
        }).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", applicationId);
        data.put("studentName", toTitleCase(studentName));
        data.put("parentNames", parentNamesFriendly);
        data.put("gradeApplied", prettyGrade(gradeApplied));
        data.put("totalInterviews", interviewRows.size());
        data.put("interviews", interviewRows);

        emailComposerService.send(EmailRequestDTO.builder()
                .template(TemplateUtils.generateTemplate("summary", data))
                .to(to)
                .subject("Resumen de entrevistas - Postulación " + applicationId + " - " + studentName)
                .recipientType("APPLICATION")
                .recipientId(applicationId)
                .data(data)
                .build());
        return Map.of("success", true, "message", "Resumen enviado", "data", Map.of("applicationId", applicationId, "interviews", interviews.stream().map(this::toResponse).toList()));
    }

    @Transactional
    public Map<String, Object> sendInterviewInvitation(Long interviewId, String bffBaseUrl) {
        InterviewEntity interview = load(interviewId);
        guardMutation(interview);
        var application = interview.getApplication();

        // Obtener email del apoderado
        String to = application.getApplicantUser() != null ? application.getApplicantUser().getEmail() : null;
        if (to == null || to.isBlank()) {
            throw new IllegalStateException("No se puede enviar la invitación: la postulación no tiene email de destinatario.");
        }

        // Datos del estudiante
        String studentName = application.getStudent() != null
                ? application.getStudent().getFirstName() + " " + application.getStudent().getPaternalLastName()
                : "Estudiante";

        // Generar URLs de confirmación (patrón pasarela)
        String confirmUrl = confirmationService.generateConfirmationUrl(bffBaseUrl, interviewId, true);
        String rejectUrl = confirmationService.generateConfirmationUrl(bffBaseUrl, interviewId, false);

        // Datos para el template (deben coincidir con INTERVIEW_INVITATION_BODY)
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", application.getId());
        data.put("studentName", toTitleCase(studentName));
        data.put("parentNames", toFriendlyParentNames(resolveParentNames(interview)));
        data.put("scheduledDate", interview.getScheduledDate().toString());
        data.put("scheduledTime", interview.getScheduledTime().toString());
        data.put("interviewType", prettyInterviewType(interview.getInterviewType()));
        data.put("mode", prettyMode(interview.getMode()));
        data.put("location", interview.getLocation());
        data.put("interviewerName", interview.getInterviewer() != null
                ? toTitleCase(interview.getInterviewer().getFirstName() + " " + interview.getInterviewer().getLastName())
                : "Por confirmar");
        data.put("confirmUrl", confirmUrl);
        data.put("rejectUrl", rejectUrl);

        // Enviar email con template INTERVIEW_INVITATION
        emailComposerService.send(EmailRequestDTO.builder()
                .template(TemplateUtils.generateTemplate("interview_invitation", data))
                .to(to)
                .subject("Invitación a entrevista - " + toTitleCase(studentName))
                .recipientType("APPLICATION")
                .recipientId(application.getId())
                .data(data)
                .build());

        return Map.of("success", true, "message", "Invitación enviada con confirmación", "data", toResponse(interview));
    }

    private void merge(InterviewEntity entity, Map<String, Object> payload) {
        if (payload.get("applicationId") instanceof Number number) {
            entity.setApplication(applicationRepository.findActiveById(number.longValue()).orElseThrow(() -> new IllegalArgumentException("Postulación no encontrada")));
        }
        if (payload.get("interviewerId") instanceof Number number) {
            entity.setInterviewer(userRepository.findById(number.longValue()).orElseThrow(() -> new IllegalArgumentException("Entrevistador no encontrado")));
        }
        if (payload.get("secondInterviewerId") instanceof Number number) {
            entity.setSecondInterviewer(userRepository.findById(number.longValue()).orElseThrow(() -> new IllegalArgumentException("Segundo entrevistador no encontrado")));
        }
        Object interviewType = payload.getOrDefault("interviewType", payload.getOrDefault("type", entity.getInterviewType() == null ? "FAMILY" : entity.getInterviewType()));
        entity.setInterviewType(String.valueOf(interviewType));
        if (payload.get("scheduledDate") != null) entity.setScheduledDate(LocalDate.parse(String.valueOf(payload.get("scheduledDate"))));
        if (payload.get("scheduledTime") != null) entity.setScheduledTime(LocalTime.parse(String.valueOf(payload.get("scheduledTime"))));
        entity.setDuration(payload.get("duration") instanceof Number number ? number.intValue() : entity.getDuration() == null ? 60 : entity.getDuration());
        entity.setLocation(payload.get("location") == null ? entity.getLocation() : String.valueOf(payload.get("location")));
        entity.setMode(payload.get("mode") == null ? entity.getMode() : String.valueOf(payload.get("mode")));
        if (payload.get("status") != null) entity.setStatus(InterviewStatus.valueOf(String.valueOf(payload.get("status")).toUpperCase()));
        entity.setNotes(payload.get("notes") == null ? entity.getNotes() : String.valueOf(payload.get("notes")));
    }

    private void guardMutation(InterviewEntity interview) {
        if (interview.getApplication() == null) {
            throw new IllegalArgumentException("La entrevista no tiene una postulación asociada");
        }
        admissionCycleGuard.assertOpen(interview.getApplication());
    }

    private boolean applyInterviewerPair(InterviewEntity entity, Map<String, Object> payload, Long excludedInterviewId) {
        if (!"CYCLE_DIRECTOR".equals(entity.getInterviewType())) {
            entity.setInterviewerPair(null);
            return false;
        }
        Long pairId = payload.get("interviewerPairId") instanceof Number number
            ? number.longValue()
            : entity.getInterviewerPair() == null ? null : entity.getInterviewerPair().getId();
        Long applicationId = entity.getApplication() == null ? null : entity.getApplication().getId();
        if (applicationId == null) {
            throw new InterviewerPairException("APPLICATION_REQUIRED", "La entrevista requiere una postulación");
        }
        InterviewerPairEntity pair = interviewerPairService.requireEligiblePair(
            pairId, applicationId, entity.getScheduledDate(), entity.getScheduledTime(), entity.getDuration(), excludedInterviewId);
        entity.setInterviewerPair(pair);
        entity.setInterviewer(pair.getCycleDirector());
        entity.setSecondInterviewer(pair.getPsychologist());
        return true;
    }

    private void validateInterviewerComposition(InterviewEntity entity) {
        if (!"FAMILY".equals(entity.getInterviewType())) return;
        if (entity.getInterviewer() == null || entity.getSecondInterviewer() == null) {
            throw new InterviewerPairException(
                "FAMILY_PAIR_REQUIRED",
                "La entrevista familiar requiere dos entrevistadores"
            );
        }
        if (entity.getInterviewer().getId().equals(entity.getSecondInterviewer().getId())) {
            throw new InterviewerPairException(
                "FAMILY_PAIR_DUPLICATED",
                "La entrevista familiar requiere dos entrevistadores distintos"
            );
        }
        Role firstRole = entity.getInterviewer().getRole();
        Role secondRole = entity.getSecondInterviewer().getRole();
        if (!isFamilyInterviewerRole(firstRole) || !isFamilyInterviewerRole(secondRole)) {
            throw new InterviewerPairException(
                "FAMILY_INTERVIEWER_ROLE_INVALID",
                "La entrevista familiar solo puede asignarse a Entrevistadores o Coordinadores"
            );
        }
    }

    static boolean isFamilyInterviewerRole(Role role) {
        return role == Role.INTERVIEWER || role == Role.COORDINATOR;
    }

    private InterviewEntity load(Long id) {
        return interviewRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Entrevista no encontrada"));
    }

    private void ensureInterviewersAvailable(InterviewEntity entity) {
        ensureInterviewerAvailable(entity.getInterviewer().getId(), entity.getScheduledDate(), entity.getScheduledTime(), entity.getDuration());
        if (entity.getSecondInterviewer() != null) {
            ensureInterviewerAvailable(entity.getSecondInterviewer().getId(), entity.getScheduledDate(), entity.getScheduledTime(), entity.getDuration());
        }
    }

    private void ensureInterviewerAvailable(Long interviewerId, LocalDate date, LocalTime time, Integer duration) {
        int interviewDuration = duration == null ? 60 : duration;
        LocalTime end = time.plusMinutes(interviewDuration);
        boolean coveredBySchedule = scheduleRepository.findAvailableTemplates(interviewerId, date, dayName(date), date.getYear()).stream()
            .anyMatch(schedule -> !time.isBefore(schedule.getStartTime()) && !end.isAfter(schedule.getEndTime()));
        if (!coveredBySchedule) {
            throw new IllegalArgumentException("El entrevistador no tiene horario disponible para la fecha y hora seleccionadas");
        }
        boolean hasConflict = interviewRepository.findBlockingForInterviewer(interviewerId, date, List.of(InterviewStatus.CANCELLED, InterviewStatus.RESCHEDULED, InterviewStatus.REJECTED_BY_FAMILY)).stream()
            .anyMatch(interview -> overlaps(time, end, interview.getScheduledTime(), interview.getScheduledTime().plusMinutes(interview.getDuration() == null ? 60 : interview.getDuration())));
        if (hasConflict) {
            throw new IllegalArgumentException("El entrevistador ya tiene una entrevista programada en este horario");
        }
    }

    private boolean overlaps(LocalTime start, LocalTime end, LocalTime bookedStart, LocalTime bookedEnd) {
        return start.isBefore(bookedEnd) && end.isAfter(bookedStart);
    }

    /**
     * Valida que no exista una entrevista activa del mismo tipo para la postulación.
     * Lanza IllegalArgumentException si ya existe una entrevista no cancelada/rechazada del mismo tipo.
     */
    private void ensureNoDuplicateInterview(Long applicationId, String interviewType, Long excludeInterviewId) {
        List<InterviewEntity> existingInterviews = interviewRepository.findByApplicationIdOrderByScheduledDateDesc(applicationId);

        boolean hasDuplicate = existingInterviews.stream()
            .filter(i -> excludeInterviewId == null || !i.getId().equals(excludeInterviewId))
            .filter(i -> i.getInterviewType() != null && i.getInterviewType().equals(interviewType))
            .anyMatch(i -> i.getStatus() != InterviewStatus.CANCELLED
                        && i.getStatus() != InterviewStatus.REJECTED_BY_FAMILY);

        if (hasDuplicate) {
            throw new IllegalArgumentException(
                "Ya existe una entrevista de tipo " + interviewType + " activa para esta postulación. " +
                "Cancele la entrevista existente antes de agendar una nueva."
            );
        }
    }

    private void createEvaluationForInterview(InterviewEntity interview) {
        if (interview.getInterviewType() == null || interview.getApplication() == null) return;

        switch (interview.getInterviewType()) {
            case "FAMILY" -> createEvaluationIfNotExists(interview, "FAMILY_INTERVIEW");
            case "CYCLE_DIRECTOR" -> {
                // CYCLE_DIRECTOR necesita DOS evaluaciones: la entrevista y el informe
                createEvaluationIfNotExists(interview, "CYCLE_DIRECTOR_INTERVIEW");
                createEvaluationIfNotExists(interview, "CYCLE_DIRECTOR_REPORT");
            }
            case "PSYCHOLOGICAL" -> createEvaluationIfNotExists(interview, "PSYCHOLOGICAL_INTERVIEW");
            default -> { /* no-op */ }
        }
    }

    private void createEvaluationIfNotExists(InterviewEntity interview, String evaluationType) {
        var existing = evaluationRepository.findByApplicationIdAndEvaluationType(
            interview.getApplication().getId(), evaluationType);
        if (existing.isPresent()) {
            EvaluationEntity evaluation = existing.get();
            boolean changed = applyInterviewAssignment(evaluation, interview);
            if (changed) evaluationRepository.save(evaluation);
            return;
        }

        EvaluationEntity evaluation = new EvaluationEntity();
        evaluation.setApplication(interview.getApplication());
        evaluation.setEvaluationType(evaluationType);
        evaluation.setStatus(EvaluationStatus.PENDING);
        applyInterviewAssignment(evaluation, interview);
        evaluationRepository.save(evaluation);
    }

    private boolean applyInterviewAssignment(EvaluationEntity evaluation, InterviewEntity interview) {
        boolean changed = false;
        if (evaluation.getEvaluator() == null && interview.getInterviewer() != null) {
            evaluation.setEvaluator(interview.getInterviewer());
            changed = true;
        }
        if (evaluation.getEvaluationDate() == null && interview.getScheduledDate() != null && interview.getScheduledTime() != null) {
            evaluation.setEvaluationDate(interview.getScheduledDate().atTime(interview.getScheduledTime()));
            changed = true;
        }
        return changed;
    }

    private String resolveParentNames(InterviewEntity entity) {
        var app = entity.getApplication();
        java.util.List<String> names = new java.util.ArrayList<>();
        if (app.getFather() != null && app.getFather().getFullName() != null) names.add(app.getFather().getFullName());
        if (app.getMother() != null && app.getMother().getFullName() != null) names.add(app.getMother().getFullName());
        if (names.isEmpty() && app.getGuardian() != null && app.getGuardian().getFullName() != null) names.add(app.getGuardian().getFullName());
        return names.isEmpty() ? null : String.join(" / ", names);
    }

    private String dayName(LocalDate date) {
        return date.getDayOfWeek().name();
    }

    private Map<String, Object> interviewerInfo(InterviewerScheduleRepository.InterviewerWithCountView interviewer) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", interviewer.getInterviewerId());
        item.put("name", (interviewer.getFirstName() + " " + interviewer.getLastName()).trim());
        item.put("role", String.valueOf(interviewer.getRole()));
        item.put("subject", interviewer.getSubject());
        item.put("scheduleCount", interviewer.getScheduleCount());
        return item;
    }

    private Map<String, Object> pickBestFamilyPair(List<Map<String, Object>> interviewers) {
        List<Map<String, Object>> sorted = interviewers.stream()
            .filter(this::isFamilyInterviewer)
            .sorted(Comparator
                .comparingInt((Map<String, Object> item) -> rolePriority(String.valueOf(item.get("role"))))
                .thenComparing(item -> String.valueOf(item.get("name"))))
            .toList();
        if (sorted.size() < 2) return null;

        Map<String, Object> first = sorted.get(0);
        Map<String, Object> second = sorted.stream()
            .filter(item -> !String.valueOf(item.get("role")).equals(String.valueOf(first.get("role"))))
            .findFirst()
            .orElse(sorted.get(1));

        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("interviewer1", first);
        pair.put("interviewer2", second);
        return pair;
    }

    private int rolePriority(String role) {
        return switch (role) {
            case "COORDINATOR" -> 0;
            case "INTERVIEWER" -> 1;
            default -> 2;
        };
    }

    private boolean isFamilyInterviewer(Map<String, Object> interviewer) {
        String role = String.valueOf(interviewer.getOrDefault("role", ""));
        return "INTERVIEWER".equals(role) || "COORDINATOR".equals(role);
    }

    private int countFamilyPairs(List<Map<String, Object>> interviewers) {
        long eligible = interviewers.stream().filter(this::isFamilyInterviewer).count();
        return eligible < 2 ? 0 : Math.toIntExact((eligible * (eligible - 1)) / 2);
    }

    private List<String> availableInterviewTypes(int familyPairCount, int cyclePairCount) {
        List<String> types = new ArrayList<>(2);
        if (familyPairCount > 0) types.add("FAMILY");
        if (cyclePairCount > 0) types.add("CYCLE_DIRECTOR");
        return types;
    }

    private String formatDayLabel(LocalDate date) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es-CL"));
        String label = date.format(formatter);
        return label.substring(0, 1).toUpperCase(Locale.forLanguageTag("es-CL")) + label.substring(1);
    }

    private Map<String, Object> wrap(List<InterviewEntity> entities) {
        List<Map<String, Object>> data = entities.stream().map(this::toResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    private Map<String, Object> toResponse(InterviewEntity entity) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entity.getId());
        response.put("applicationId", entity.getApplication().getId());
        response.put("interviewerId", entity.getInterviewer() == null ? null : entity.getInterviewer().getId());
        response.put("secondInterviewerId", entity.getSecondInterviewer() == null ? null : entity.getSecondInterviewer().getId());
        response.put("interviewerPairId", entity.getInterviewerPair() == null ? null : entity.getInterviewerPair().getId());
        response.put("interviewerPairRevision", entity.getInterviewerPair() == null ? null : entity.getInterviewerPair().getRevision());
        response.put("interviewType", entity.getInterviewType());
        response.put("scheduledDate", entity.getScheduledDate());
        response.put("scheduledTime", entity.getScheduledTime());
        response.put("duration", entity.getDuration());
        response.put("location", entity.getLocation());
        response.put("mode", entity.getMode());
        response.put("status", entity.getStatus().name());
        response.put("notes", entity.getNotes());
        response.put("studentName", entity.getApplication().getStudent() == null ? null : entity.getApplication().getStudent().getFirstName() + " " + entity.getApplication().getStudent().getPaternalLastName() + " " + entity.getApplication().getStudent().getMaternalLastName());
        response.put("parentNames", resolveParentNames(entity));
        response.put("interviewerName", entity.getInterviewer() == null ? null : entity.getInterviewer().getFirstName() + " " + entity.getInterviewer().getLastName());
        response.put("secondInterviewerName", entity.getSecondInterviewer() == null ? null : entity.getSecondInterviewer().getFirstName() + " " + entity.getSecondInterviewer().getLastName());
        response.put("gradeApplied", entity.getApplication().getStudent() == null ? null : entity.getApplication().getStudent().getGradeApplied());
        return response;
    }

    private Map<String, Object> toCalendarResponse(InterviewEntity entity) {
        Map<String, Object> response = new LinkedHashMap<>(toResponse(entity));
        response.put("title", response.get("studentName") + " - " + response.get("interviewType"));
        response.put("start", entity.getScheduledDate() + "T" + entity.getScheduledTime());
        response.put("end", entity.getScheduledDate() + "T" + entity.getScheduledTime().plusMinutes(entity.getDuration()));
        return response;
    }

    // ----------------------------------------------------------------
    // Helpers de formato para el correo de resumen (tono familiar).
    // ----------------------------------------------------------------

    /**
     * Convierte "JUAN PEREZ / PRUEBA MAMA" en "Juan y Prueba" (solo primer
     * nombre de cada apoderado, capitalizado, unidos con "y").
     */
    private String toFriendlyParentNames(String raw) {
        if (raw == null || raw.isBlank()) return "familia";
        String[] parts = raw.split("/");
        List<String> firstNames = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String firstName = trimmed.split("\\s+")[0];
            firstNames.add(toTitleCase(firstName));
        }
        if (firstNames.isEmpty()) return "familia";
        if (firstNames.size() == 1) return firstNames.get(0);
        if (firstNames.size() == 2) return firstNames.get(0) + " y " + firstNames.get(1);
        String last = firstNames.remove(firstNames.size() - 1);
        return String.join(", ", firstNames) + " y " + last;
    }

    /** "JUAN PEREZ" -> "Juan Perez". */
    /**
     * Genera un resumen semanal de entrevistas para el centro operativo.
     * Incluye entrevistas rechazadas por familia para gestión.
     */
    public Map<String, Object> weeklyOverview(String startDateStr, String endDateStr, Integer defaultDuration) {
        LocalDate startDate = LocalDate.parse(startDateStr);
        LocalDate endDate = LocalDate.parse(endDateStr);
        int duration = defaultDuration != null ? defaultDuration : 30;

        // Obtener todas las entrevistas del rango (INCLUYENDO rechazadas para gestión)
        List<InterviewEntity> allInterviews = interviewRepository
            .findByScheduledDateGreaterThanEqualAndScheduledDateLessThanEqualOrderByScheduledDateAscScheduledTimeAsc(startDate, endDate);

        // Agrupar por día
        List<Map<String, Object>> days = new ArrayList<>();
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            final LocalDate dayDate = current;
            List<InterviewEntity> dayInterviews = allInterviews.stream()
                .filter(i -> i.getScheduledDate().equals(dayDate))
                .toList();

            // Entrevistas programadas del día (incluye rechazadas para gestión)
            List<Map<String, Object>> scheduled = dayInterviews.stream().map(i -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", i.getId());
                item.put("time", i.getScheduledTime().toString());
                item.put("endTime", i.getScheduledTime().plusMinutes(i.getDuration()).toString());
                item.put("studentName", i.getApplication().getStudent() != null
                    ? i.getApplication().getStudent().getFirstName() + " " + i.getApplication().getStudent().getPaternalLastName()
                    : "Sin nombre");
                item.put("applicationId", i.getApplication().getId());
                item.put("interviewType", i.getInterviewType());
                item.put("mode", i.getMode());
                item.put("status", i.getStatus().name());
                item.put("interviewer1", Map.of(
                    "id", i.getInterviewer() != null ? i.getInterviewer().getId() : 0,
                    "name", i.getInterviewer() != null ? i.getInterviewer().getFirstName() + " " + i.getInterviewer().getLastName() : "Sin asignar",
                    "role", i.getInterviewer() != null ? String.valueOf(i.getInterviewer().getRole()) : "UNKNOWN"
                ));
                if (i.getSecondInterviewer() != null) {
                    item.put("interviewer2", Map.of(
                        "id", i.getSecondInterviewer().getId(),
                        "name", i.getSecondInterviewer().getFirstName() + " " + i.getSecondInterviewer().getLastName(),
                        "role", String.valueOf(i.getSecondInterviewer().getRole())
                    ));
                }
                return item;
            }).toList();

            // Contar para estadísticas
            long scheduledCount = dayInterviews.stream().filter(i ->
                i.getStatus() == InterviewStatus.SCHEDULED ||
                i.getStatus() == InterviewStatus.CONFIRMED
            ).count();
            long completedCount = dayInterviews.stream().filter(i ->
                i.getStatus() == InterviewStatus.COMPLETED
            ).count();
            long cancelledCount = dayInterviews.stream().filter(i ->
                i.getStatus() == InterviewStatus.CANCELLED ||
                i.getStatus() == InterviewStatus.REJECTED_BY_FAMILY
            ).count();

            // Calcular slots disponibles para este día
            List<Map<String, Object>> availableSlots = calculateAvailableSlotsForDay(dayDate, duration);

            Map<String, Object> dayData = new LinkedHashMap<>();
            dayData.put("date", dayDate.toString());
            dayData.put("dayOfWeek", dayDate.getDayOfWeek().toString().substring(0, 3).toUpperCase());
            dayData.put("dayLabel", formatDayLabel(dayDate));
            dayData.put("scheduled", scheduled);
            dayData.put("available", availableSlots);
            dayData.put("summary", Map.of(
                "scheduledCount", scheduledCount,
                "completedCount", completedCount,
                "cancelledCount", cancelledCount,
                "totalCount", dayInterviews.size()
            ));
            days.add(dayData);

            current = current.plusDays(1);
        }

        // Estadísticas generales
        long totalScheduled = allInterviews.stream().filter(i ->
            i.getStatus() == InterviewStatus.SCHEDULED || i.getStatus() == InterviewStatus.CONFIRMED
        ).count();
        long totalCompleted = allInterviews.stream().filter(i ->
            i.getStatus() == InterviewStatus.COMPLETED
        ).count();
        long totalCancelled = allInterviews.stream().filter(i ->
            i.getStatus() == InterviewStatus.CANCELLED
        ).count();
        long totalRejected = allInterviews.stream().filter(i ->
            i.getStatus() == InterviewStatus.REJECTED_BY_FAMILY
        ).count();

        // Contar slots disponibles totales
        long totalAvailableSlots = days.stream()
            .flatMap(d -> ((List<Map<String, Object>>) d.get("available")).stream())
            .count();
        long singleInterviewerSlots = days.stream()
            .flatMap(d -> ((List<Map<String, Object>>) d.get("available")).stream())
            .filter(s -> (int) s.getOrDefault("interviewerCount", 0) == 1)
            .count();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", Map.of(
            "range", Map.of(
                "startDate", startDateStr,
                "endDate", endDateStr,
                "totalDays", java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1
            ),
            "summary", Map.of(
                "scheduledCount", totalScheduled,
                "completedCount", totalCompleted,
                "cancelledCount", totalCancelled,
                "rejectedCount", totalRejected,
                "availableSlotsCount", totalAvailableSlots,
                "singleInterviewerSlotsCount", singleInterviewerSlots
            ),
            "interviewerLoad", List.of(),
            "days", days
        ));

        return response;
    }

    /**
     * Calcula los slots disponibles para un día específico.
     * Agrupa entrevistadores disponibles por horario.
     */
    private List<Map<String, Object>> calculateAvailableSlotsForDay(LocalDate date, int duration) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        // Obtener entrevistadores con horarios para este día
        List<Map<String, Object>> interviewersWithSchedules = scheduleRepository.findInterviewersWithSchedules(date.getYear()).stream()
            .filter(interviewer -> {
                List<InterviewerScheduleEntity> schedules = scheduleRepository.findAvailableTemplates(
                    interviewer.getInterviewerId(), date, dayName(date), date.getYear()
                );
                return !schedules.isEmpty();
            })
            .map(interviewer -> {
                List<InterviewerScheduleEntity> schedules = scheduleRepository.findAvailableTemplates(
                    interviewer.getInterviewerId(), date, dayName(date), date.getYear()
                );
                List<InterviewEntity> booked = interviewRepository.findBlockingForInterviewer(
                    interviewer.getInterviewerId(), date,
                    List.of(InterviewStatus.CANCELLED, InterviewStatus.RESCHEDULED, InterviewStatus.REJECTED_BY_FAMILY)
                );

                // Generar slots para este entrevistador
                List<LocalTime> availableSlots = new ArrayList<>();
                for (InterviewerScheduleEntity schedule : schedules) {
                    LocalTime current = schedule.getStartTime();
                    while (current.isBefore(schedule.getEndTime())) {
                        LocalTime slot = current;
                        // Skip slots en el pasado
                        if (date.equals(today) && slot.isBefore(now)) {
                            current = current.plusMinutes(30);
                            continue;
                        }
                        LocalTime slotEnd = slot.plusMinutes(duration);
                        boolean canFitDuration = !slotEnd.isAfter(schedule.getEndTime());
                        boolean occupied = booked.stream().anyMatch(interview ->
                            overlaps(slot, slotEnd, interview.getScheduledTime(),
                                interview.getScheduledTime().plusMinutes(interview.getDuration() == null ? 60 : interview.getDuration()))
                        );
                        if (canFitDuration && !occupied) {
                            availableSlots.add(slot);
                        }
                        current = current.plusMinutes(30);
                    }
                }

                return Map.of(
                    "interviewerId", interviewer.getInterviewerId(),
                    "name", (interviewer.getFirstName() + " " + interviewer.getLastName()).trim(),
                    "role", String.valueOf(interviewer.getRole()),
                    "availableSlots", availableSlots
                );
            })
            .toList();

        // Agrupar slots por horario y recolectar entrevistadores disponibles
        Map<LocalTime, List<Map<String, Object>>> slotsByTime = new HashMap<>();
        for (Map<String, Object> interviewerData : interviewersWithSchedules) {
            @SuppressWarnings("unchecked")
            List<LocalTime> slots = (List<LocalTime>) interviewerData.get("availableSlots");
            for (LocalTime slot : slots) {
                slotsByTime.computeIfAbsent(slot, k -> new ArrayList<>()).add(Map.of(
                    "id", interviewerData.get("interviewerId"),
                    "name", interviewerData.get("name"),
                    "role", interviewerData.get("role")
                ));
            }
        }

        // Construir resultado final ordenado por hora
        return slotsByTime.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> {
                LocalTime time = entry.getKey();
                List<Map<String, Object>> availableInterviewers = entry.getValue();

                Map<String, Object> slotData = new LinkedHashMap<>();
                slotData.put("time", time.toString());
                slotData.put("availableInterviewers", availableInterviewers);
                slotData.put("interviewerCount", availableInterviewers.size());
                List<Map<String, Object>> availablePairs = interviewerPairService.availablePairs(date, time, duration);
                slotData.put("availablePairs", availablePairs);
                slotData.put("availablePairCount", availablePairs.size());
                int familyPairCount = countFamilyPairs(availableInterviewers);
                slotData.put("familyPairCount", familyPairCount);
                slotData.put("availableInterviewTypes", availableInterviewTypes(familyPairCount, availablePairs.size()));

                // La sugerencia del endpoint general corresponde siempre a entrevista familiar.
                if (familyPairCount > 0) {
                    Map<String, Object> pair = pickBestFamilyPair(availableInterviewers);
                    if (pair != null) {
                        slotData.put("suggestedPair", pair);
                    }
                }

                return slotData;
            })
            .filter(slot -> ((int) slot.getOrDefault("familyPairCount", 0)) > 0
                || ((int) slot.getOrDefault("availablePairCount", 0)) > 0)
            .toList();
    }

    private String toTitleCase(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        boolean newWord = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '-' || c == '/') {
                sb.append(c);
                newWord = true;
            } else if (newWord) {
                sb.append(Character.toUpperCase(c));
                newWord = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** "7_BASICO" -> "7° Básico". */
    private String prettyGrade(String grade) {
        if (grade == null || grade.isBlank()) return "";
        String normalized = grade.replace('_', ' ').toLowerCase();
        // Capitaliza palabras y agrega "°" al primer número si lo hay.
        String titled = toTitleCase(normalized);
        return titled.replaceFirst("^(\\d+)\\s", "$1° ");
    }

    private String prettyInterviewType(String type) {
        if (type == null) return "";
        return switch (type.toUpperCase()) {
            case "FAMILY"          -> "Entrevista familiar";
            case "CYCLE_DIRECTOR"  -> "Director de ciclo";
            case "PSYCHOLOGIST"    -> "Psicólogo/a";
            case "ACADEMIC"        -> "Académica";
            case "DIRECTOR"        -> "Dirección";
            default                -> toTitleCase(type.replace('_', ' '));
        };
    }

    private String prettyMode(String mode) {
        if (mode == null) return "";
        return switch (mode.toUpperCase()) {
            case "IN_PERSON" -> "Presencial";
            case "ONLINE"    -> "Online";
            case "HYBRID"    -> "Híbrida";
            default          -> toTitleCase(mode.replace('_', ' '));
        };
    }
}
