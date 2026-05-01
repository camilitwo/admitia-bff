package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.common.ScheduleType;
import cl.mtn.admitiabff.domain.interview.InterviewerScheduleEntity;
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
public class InterviewerScheduleService {
    private final InterviewerScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final InterviewRepository interviewRepository;
    private final AuthService authService;

    public InterviewerScheduleService(InterviewerScheduleRepository scheduleRepository, UserRepository userRepository, InterviewRepository interviewRepository, AuthService authService) {
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
        this.interviewRepository = interviewRepository;
        this.authService = authService;
    }

    public List<Map<String, Object>> byInterviewer(Long interviewerId) { return scheduleRepository.findByInterviewerIdOrderByYearDescDayOfWeekAscStartTimeAsc(interviewerId).stream().map(this::toResponse).toList(); }
    public List<Map<String, Object>> byInterviewerAndYear(Long interviewerId, Integer year) { return scheduleRepository.findByInterviewerIdAndYearOrderByDayOfWeekAscStartTimeAsc(interviewerId, year).stream().map(this::toResponse).toList(); }

    public Map<String, Object> available(String date, String time) {
        LocalDate targetDate = LocalDate.parse(date);
        LocalTime targetTime = LocalTime.parse(time);
        List<Map<String, Object>> interviewers = scheduleRepository.findInterviewersWithSchedules(targetDate.getYear()).stream()
            .filter(item -> scheduleRepository.findAvailableTemplates(item.getInterviewerId(), targetDate, targetDate.getDayOfWeek().getValue()).stream().anyMatch(schedule -> !targetTime.isBefore(schedule.getStartTime()) && targetTime.isBefore(schedule.getEndTime())))
            .filter(item -> interviewRepository.findByInterviewerIdAndScheduledDateAndStatusIn(item.getInterviewerId(), targetDate, List.of(cl.mtn.admitiabff.domain.common.InterviewStatus.SCHEDULED, cl.mtn.admitiabff.domain.common.InterviewStatus.RESCHEDULED)).stream().noneMatch(interview -> targetTime.equals(interview.getScheduledTime())))
            .<Map<String, Object>>map(item -> {
                Map<String, Object> interviewer = new LinkedHashMap<>();
                interviewer.put("id", item.getInterviewerId());
                interviewer.put("firstName", item.getFirstName());
                interviewer.put("lastName", item.getLastName());
                interviewer.put("name", item.getFirstName() + " " + item.getLastName());
                interviewer.put("email", item.getEmail());
                interviewer.put("role", String.valueOf(item.getRole()));
                interviewer.put("subject", item.getSubject());
                return interviewer;
            })
            .toList();
        return Map.of("success", true, "date", date, "time", time, "dayOfWeek", targetDate.getDayOfWeek().getValue(), "count", interviewers.size(), "interviewers", interviewers);
    }

    public List<Map<String, Object>> interviewersWithSchedules(Integer year) {
        return scheduleRepository.findInterviewersWithSchedules(year).stream()
            .<Map<String, Object>>map(item -> {
                Map<String, Object> interviewer = new LinkedHashMap<>();
                interviewer.put("id", item.getInterviewerId());
                interviewer.put("firstName", item.getFirstName());
                interviewer.put("lastName", item.getLastName());
                interviewer.put("email", item.getEmail());
                interviewer.put("role", String.valueOf(item.getRole()));
                interviewer.put("subject", item.getSubject());
                interviewer.put("scheduleCount", item.getScheduleCount());
                return interviewer;
            })
            .toList();
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        Long interviewerId = resolveInterviewerId(payload);
        enforceOwnership(interviewerId);
        Integer dayOfWeek = payload.get("dayOfWeek") == null ? null : Integer.parseInt(String.valueOf(payload.get("dayOfWeek")));
        LocalTime startTime = LocalTime.parse(String.valueOf(payload.get("startTime")));
        LocalTime endTime = LocalTime.parse(String.valueOf(payload.get("endTime")));
        Integer year = Integer.parseInt(String.valueOf(payload.get("year")));
        LocalDate specificDate = payload.get("specificDate") == null || String.valueOf(payload.get("specificDate")).isBlank() ? null : LocalDate.parse(String.valueOf(payload.get("specificDate")));
        if (scheduleRepository.existsDuplicate(interviewerId, dayOfWeek, startTime, endTime, year, specificDate)) {
            throw new IllegalArgumentException("Ya existe un horario idéntico");
        }
        InterviewerScheduleEntity entity = new InterviewerScheduleEntity();
        merge(entity, payload, interviewerId);
        return Map.of("success", true, "message", "Horario creado", "data", toResponse(scheduleRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> createRecurring(Long interviewerId, Integer year, List<Map<String, Object>> payload) {
        enforceOwnership(interviewerId);
        List<Map<String, Object>> created = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Map<String, Object> item : payload) {
            Map<String, Object> request = new LinkedHashMap<>(item);
            request.put("interviewerId", interviewerId);
            request.put("year", year);
            request.put("scheduleType", ScheduleType.RECURRING.name());
            Integer dayOfWeek = Integer.parseInt(String.valueOf(item.get("dayOfWeek")));
            LocalTime startTime = LocalTime.parse(String.valueOf(item.get("startTime")));
            LocalTime endTime = LocalTime.parse(String.valueOf(item.get("endTime")));
            if (scheduleRepository.existsDuplicate(interviewerId, dayOfWeek, startTime, endTime, year, null)) {
                skipped.add(item);
                continue;
            }
            created.add((Map<String, Object>) create(request).get("data"));
        }
        return Map.of("success", true, "data", Map.of("created", created, "skipped", skipped, "status", skipped.isEmpty() ? 201 : 207));
    }

    @Transactional
    public Map<String, Object> toggle(Map<String, Object> payload) {
        Long interviewerId = resolveInterviewerId(payload);
        LocalDate specificDate = LocalDate.parse(String.valueOf(payload.get("specificDate")));
        LocalTime startTime = LocalTime.parse(String.valueOf(payload.get("startTime")));
        LocalTime endTime = LocalTime.parse(String.valueOf(payload.get("endTime")));
        Integer year = Integer.parseInt(String.valueOf(payload.get("year")));
        var current = scheduleRepository.findByInterviewerIdAndSpecificDateAndStartTimeAndEndTimeAndYear(interviewerId, specificDate, startTime, endTime, year);
        if (current.isEmpty()) {
            return create(payload);
        }
        InterviewerScheduleEntity entity = current.get();
        entity.setActive(!entity.isActive());
        return Map.of("success", true, "message", entity.isActive() ? "Slot activado" : "Slot desactivado", "data", toResponse(scheduleRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> toggleBulk(Map<String, Object> payload) {
        LocalTime current = LocalTime.parse(String.valueOf(payload.get("startTime")));
        LocalTime end = LocalTime.parse(String.valueOf(payload.get("endTime")));
        List<Map<String, Object>> processed = new ArrayList<>();
        while (current.isBefore(end)) {
            LocalTime next = current.plusMinutes(30);
            Map<String, Object> request = new LinkedHashMap<>(payload);
            request.put("startTime", current.toString());
            request.put("endTime", next.toString());
            processed.add((Map<String, Object>) toggle(request).get("data"));
            current = next;
        }
        return Map.of("success", true, "data", Map.of("processed", processed, "status", 207));
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> payload) {
        InterviewerScheduleEntity entity = scheduleRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Horario no encontrado"));
        merge(entity, payload, entity.getInterviewer().getId());
        return Map.of("success", true, "message", "Horario actualizado", "data", toResponse(scheduleRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> deactivate(Long id) {
        InterviewerScheduleEntity entity = scheduleRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Horario no encontrado"));
        entity.setActive(false);
        return Map.of("success", true, "message", "Horario desactivado", "data", toResponse(scheduleRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        scheduleRepository.deleteById(id);
        return Map.of("success", true, "message", "Horario eliminado correctamente");
    }

    private void merge(InterviewerScheduleEntity entity, Map<String, Object> payload, Long interviewerId) {
        entity.setInterviewer(userRepository.findById(interviewerId).orElseThrow(() -> new IllegalArgumentException("Entrevistador no encontrado")));
        entity.setDayOfWeek(payload.get("dayOfWeek") == null || String.valueOf(payload.get("dayOfWeek")).isBlank() ? entity.getDayOfWeek() : Integer.parseInt(String.valueOf(payload.get("dayOfWeek"))));
        entity.setStartTime(payload.get("startTime") == null ? entity.getStartTime() : LocalTime.parse(String.valueOf(payload.get("startTime"))));
        entity.setEndTime(payload.get("endTime") == null ? entity.getEndTime() : LocalTime.parse(String.valueOf(payload.get("endTime"))));
        entity.setYear(payload.get("year") == null ? entity.getYear() : Integer.parseInt(String.valueOf(payload.get("year"))));
        entity.setSpecificDate(payload.get("specificDate") == null || String.valueOf(payload.get("specificDate")).isBlank() ? entity.getSpecificDate() : LocalDate.parse(String.valueOf(payload.get("specificDate"))));
        entity.setScheduleType(payload.get("scheduleType") == null ? entity.getScheduleType() == null ? ScheduleType.RECURRING : entity.getScheduleType() : ScheduleType.valueOf(String.valueOf(payload.get("scheduleType")).toUpperCase()));
        entity.setNotes(payload.get("notes") == null ? entity.getNotes() : String.valueOf(payload.get("notes")));
        if (payload.get("isActive") != null) entity.setActive(Boolean.parseBoolean(String.valueOf(payload.get("isActive"))));
    }

    private Long resolveInterviewerId(Map<String, Object> payload) {
        if (payload.get("interviewerId") instanceof Number number) return number.longValue();
        if (payload.get("interviewer") instanceof Map<?, ?> map && map.get("id") instanceof Number number) return number.longValue();
        if (payload.get("interviewer") instanceof Number number) return number.longValue();
        throw new IllegalArgumentException("Debe indicar interviewerId");
    }

    private void enforceOwnership(Long interviewerId) {
        AuthService.AuthContextHolder auth = authService.requireAuth();
        if (Role.INTERVIEWER.name().equals(auth.role()) && !auth.id().equals(interviewerId)) {
            throw new IllegalArgumentException("Un entrevistador solo puede gestionar sus propios horarios");
        }
    }

    private Map<String, Object> toResponse(InterviewerScheduleEntity entity) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entity.getId());
        response.put("interviewer", Map.of("id", entity.getInterviewer().getId(), "firstName", entity.getInterviewer().getFirstName(), "lastName", entity.getInterviewer().getLastName(), "email", entity.getInterviewer().getEmail(), "role", entity.getInterviewer().getRole().name()));
        response.put("dayOfWeek", entity.getDayOfWeek());
        response.put("startTime", entity.getStartTime());
        response.put("endTime", entity.getEndTime());
        response.put("year", entity.getYear());
        response.put("specificDate", entity.getSpecificDate());
        response.put("scheduleType", entity.getScheduleType().name());
        response.put("isActive", entity.isActive());
        response.put("notes", entity.getNotes());
        response.put("createdAt", entity.getCreatedAt());
        response.put("updatedAt", entity.getUpdatedAt());
        return response;
    }
}
