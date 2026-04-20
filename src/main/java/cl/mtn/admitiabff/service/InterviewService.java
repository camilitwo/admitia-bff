package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.interview.InterviewEntity;
import cl.mtn.admitiabff.domain.interview.InterviewerScheduleEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.InterviewerScheduleRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InterviewService {
    private final InterviewRepository interviewRepository;
    private final InterviewerScheduleRepository scheduleRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public InterviewService(InterviewRepository interviewRepository, InterviewerScheduleRepository scheduleRepository, ApplicationRepository applicationRepository, UserRepository userRepository, NotificationService notificationService) {
        this.interviewRepository = interviewRepository;
        this.scheduleRepository = scheduleRepository;
        this.applicationRepository = applicationRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    public List<Map<String, Object>> publicInterviewers() {
        return scheduleRepository.findInterviewersWithSchedules(LocalDate.now().getYear()).stream()
            .map(item -> {
                Map<String, Object> interviewer = new LinkedHashMap<>();
                interviewer.put("id", item.getInterviewerId());
                interviewer.put("name", item.getFirstName() + " " + item.getLastName());
                interviewer.put("role", String.valueOf(item.getRole()));
                interviewer.put("subject", item.getSubject());
                interviewer.put("educationalLevel", null);
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

    public Map<String, Object> calendar(String startDate, String endDate) {
        LocalDate start = startDate == null || startDate.isBlank() ? null : LocalDate.parse(startDate);
        LocalDate end = endDate == null || endDate.isBlank() ? null : LocalDate.parse(endDate);
        List<Map<String, Object>> data = interviewRepository.findForCalendar(start, end).stream().map(this::toCalendarResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    public Map<String, Object> byApplication(Long applicationId) { return wrap(interviewRepository.findByApplicationIdOrderByScheduledDateDesc(applicationId)); }
    public Map<String, Object> summaryStatus(Long applicationId) { return Map.of("success", true, "data", Map.of("applicationId", applicationId, "summarySent", interviewRepository.countByApplicationIdAndSummarySentTrue(applicationId) > 0)); }
    public List<Map<String, Object>> byInterviewer(Long interviewerId) { return interviewRepository.findVisibleForInterviewer(interviewerId, List.of(InterviewStatus.CANCELLED, InterviewStatus.RESCHEDULED)).stream().map(this::toResponse).toList(); }

    public Map<String, Object> availableSlots(Long interviewerId, String date, Integer duration) {
        LocalDate targetDate = LocalDate.parse(date);
        int interviewDuration = duration == null ? 60 : duration;
        List<InterviewerScheduleEntity> schedules = scheduleRepository.findAvailableTemplates(interviewerId, targetDate, targetDate.getDayOfWeek().getValue());
        List<InterviewEntity> booked = interviewRepository.findByInterviewerIdAndScheduledDateAndStatusIn(interviewerId, targetDate, List.of(InterviewStatus.SCHEDULED, InterviewStatus.RESCHEDULED));
        List<Map<String, Object>> slots = new ArrayList<>();
        for (InterviewerScheduleEntity schedule : schedules) {
            LocalTime current = schedule.getStartTime();
            while (!current.plusMinutes(30).isAfter(schedule.getEndTime())) {
                LocalTime slot = current;
                boolean occupied = booked.stream().anyMatch(interview -> slot.equals(interview.getScheduledTime()));
                if (!occupied) {
                    slots.add(Map.of("time", slot.toString(), "display", slot.toString(), "canFitDuration", !slot.plusMinutes(interviewDuration).isAfter(schedule.getEndTime())));
                }
                current = current.plusMinutes(30);
            }
        }
        return Map.of("success", true, "data", Map.of("availableSlots", slots, "date", date, "interviewerId", interviewerId, "duration", interviewDuration));
    }

    public Map<String, Object> get(Long id) { return toResponse(load(id)); }

    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        InterviewEntity entity = new InterviewEntity();
        merge(entity, payload);
        return Map.of("success", true, "message", "Entrevista creada correctamente", "data", toResponse(interviewRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> payload) {
        InterviewEntity entity = load(id);
        merge(entity, payload);
        return Map.of("success", true, "message", "Entrevista actualizada", "data", toResponse(interviewRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        interviewRepository.deleteById(id);
        return Map.of("success", true, "message", "Entrevista eliminada correctamente");
    }

    @Transactional
    public Map<String, Object> cancel(Long id, Map<String, Object> payload) {
        InterviewEntity entity = load(id);
        entity.setStatus(InterviewStatus.CANCELLED);
        if (payload != null && payload.get("reason") != null) entity.setNotes(String.valueOf(payload.get("reason")));
        return Map.of("success", true, "message", "Entrevista cancelada", "data", toResponse(interviewRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> reschedule(Long id, Map<String, Object> payload) {
        InterviewEntity entity = load(id);
        entity.setScheduledDate(LocalDate.parse(String.valueOf(payload.get("scheduledDate"))));
        entity.setScheduledTime(LocalTime.parse(String.valueOf(payload.get("scheduledTime"))));
        entity.setStatus(InterviewStatus.RESCHEDULED);
        entity.setNotes(payload.get("notes") == null ? entity.getNotes() : String.valueOf(payload.get("notes")));
        return Map.of("success", true, "message", "Entrevista reprogramada", "data", toResponse(interviewRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> sendSummary(Long applicationId) {
        List<InterviewEntity> interviews = interviewRepository.findByApplicationIdOrderByScheduledDateDesc(applicationId);
        interviews.forEach(interview -> interview.setSummarySent(true));
        interviewRepository.saveAll(interviews);
        notificationService.recordEmail(Map.of("to", "admision@mtn.cl", "subject", "Resumen de entrevistas", "message", "Resumen enviado para la postulación " + applicationId, "type", "INTERVIEW_SUMMARY"));
        return Map.of("success", true, "message", "Resumen enviado", "data", Map.of("applicationId", applicationId, "interviews", interviews.stream().map(this::toResponse).toList()));
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
        entity.setInterviewType(String.valueOf(payload.getOrDefault("interviewType", entity.getInterviewType() == null ? "FAMILY" : entity.getInterviewType())));
        if (payload.get("scheduledDate") != null) entity.setScheduledDate(LocalDate.parse(String.valueOf(payload.get("scheduledDate"))));
        if (payload.get("scheduledTime") != null) entity.setScheduledTime(LocalTime.parse(String.valueOf(payload.get("scheduledTime"))));
        entity.setDuration(payload.get("duration") instanceof Number number ? number.intValue() : entity.getDuration() == null ? 60 : entity.getDuration());
        entity.setLocation(payload.get("location") == null ? entity.getLocation() : String.valueOf(payload.get("location")));
        entity.setMode(payload.get("mode") == null ? entity.getMode() : String.valueOf(payload.get("mode")));
        if (payload.get("status") != null) entity.setStatus(InterviewStatus.valueOf(String.valueOf(payload.get("status")).toUpperCase()));
        entity.setNotes(payload.get("notes") == null ? entity.getNotes() : String.valueOf(payload.get("notes")));
    }

    private InterviewEntity load(Long id) {
        return interviewRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Entrevista no encontrada"));
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
        response.put("interviewType", entity.getInterviewType());
        response.put("scheduledDate", entity.getScheduledDate());
        response.put("scheduledTime", entity.getScheduledTime());
        response.put("duration", entity.getDuration());
        response.put("location", entity.getLocation());
        response.put("mode", entity.getMode());
        response.put("status", entity.getStatus().name());
        response.put("notes", entity.getNotes());
        response.put("studentName", entity.getApplication().getStudent() == null ? null : entity.getApplication().getStudent().getFirstName() + " " + entity.getApplication().getStudent().getPaternalLastName() + " " + entity.getApplication().getStudent().getMaternalLastName());
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
}
