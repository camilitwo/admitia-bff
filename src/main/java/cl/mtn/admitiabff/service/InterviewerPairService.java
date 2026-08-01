package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.interview.InterviewEntity;
import cl.mtn.admitiabff.domain.interview.InterviewerPairEntity;
import cl.mtn.admitiabff.domain.interview.InterviewerScheduleEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.InterviewerPairRepository;
import cl.mtn.admitiabff.repository.InterviewerScheduleRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InterviewerPairService {
    public static final List<String> GRADE_CODES = List.of(
        "PRE_KINDER", "KINDER",
        "1_BASICO", "2_BASICO", "3_BASICO", "4_BASICO",
        "5_BASICO", "6_BASICO", "7_BASICO", "8_BASICO",
        "1_MEDIO", "2_MEDIO", "3_MEDIO", "4_MEDIO"
    );

    private static final List<InterviewStatus> NON_BLOCKING_STATUSES = List.of(
        InterviewStatus.CANCELLED, InterviewStatus.RESCHEDULED, InterviewStatus.REJECTED_BY_FAMILY
    );

    private final InterviewerPairRepository pairRepository;
    private final UserRepository userRepository;
    private final ApplicationRepository applicationRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewerScheduleRepository scheduleRepository;

    public InterviewerPairService(InterviewerPairRepository pairRepository,
                                  UserRepository userRepository,
                                  ApplicationRepository applicationRepository,
                                  InterviewRepository interviewRepository,
                                  InterviewerScheduleRepository scheduleRepository) {
        this.pairRepository = pairRepository;
        this.userRepository = userRepository;
        this.applicationRepository = applicationRepository;
        this.interviewRepository = interviewRepository;
        this.scheduleRepository = scheduleRepository;
    }

    public Map<String, Object> all() {
        List<Map<String, Object>> data = pairRepository.findAllByOrderByActiveDescCreatedAtDesc().stream()
            .map(this::toResponse)
            .toList();
        return Map.of("success", true, "data", data, "count", data.size(), "gradeCatalog", gradeCatalog());
    }

    public Map<String, Object> options() {
        List<Map<String, Object>> directors = userRepository.findByRoleOrderByFirstNameAscLastNameAsc(Role.CYCLE_DIRECTOR).stream()
            .filter(UserEntity::isActive).map(this::memberOptionResponse).toList();
        List<Map<String, Object>> psychologists = userRepository.findByRoleOrderByFirstNameAscLastNameAsc(Role.PSYCHOLOGIST).stream()
            .filter(UserEntity::isActive).map(this::memberOptionResponse).toList();
        return Map.of("success", true, "data", Map.of(
            "cycleDirectors", directors,
            "psychologists", psychologists,
            "grades", gradeCatalog()
        ));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        InterviewerPairEntity pair = buildPair(payload, null);
        return Map.of("success", true, "message", "Pareja creada correctamente", "data", toResponse(pairRepository.save(pair)));
    }

    @Transactional
    public Map<String, Object> revise(Long id, Map<String, Object> payload) {
        InterviewerPairEntity previous = load(id);
        if (!previous.isActive()) {
            throw new InterviewerPairException("PAIR_INACTIVE", "La pareja archivada no puede modificarse");
        }
        previous.setActive(false);
        previous.setArchivedAt(LocalDateTime.now());
        pairRepository.saveAndFlush(previous);

        InterviewerPairEntity revised = buildPair(payload, previous);
        revised.setRevision(previous.getRevision() + 1);
        revised.setSupersedesPair(previous);
        return Map.of("success", true, "message", "Nueva revisión de la pareja creada", "data", toResponse(pairRepository.save(revised)));
    }

    @Transactional
    public Map<String, Object> archive(Long id) {
        InterviewerPairEntity pair = load(id);
        if (pair.isActive()) {
            pair.setActive(false);
            pair.setArchivedAt(LocalDateTime.now());
            pairRepository.save(pair);
        }
        return Map.of("success", true, "message", "Pareja archivada", "data", toResponse(pair));
    }

    public Map<String, Object> eligible(Long applicationId, String dateValue, String timeValue, Integer durationValue) {
        var application = applicationRepository.findActiveById(applicationId)
            .orElseThrow(() -> new InterviewerPairException("APPLICATION_NOT_FOUND", "Postulación no encontrada"));
        String rawGrade = application.getStudent() == null ? null : application.getStudent().getGradeApplied();
        String grade = normalizeGrade(rawGrade);
        if (grade == null) {
            return eligibilityResponse(applicationId, rawGrade, "GRADE_MISSING", "La postulación no tiene un curso válido", List.of());
        }

        List<InterviewerPairEntity> allActive = pairRepository.findByActiveTrueOrderByCycleDirectorFirstNameAscCycleDirectorLastNameAsc();
        if (allActive.isEmpty()) {
            return eligibilityResponse(applicationId, grade, "NO_PAIRS_CONFIGURED", "No existen parejas activas configuradas", List.of());
        }
        List<InterviewerPairEntity> gradePairs = pairRepository.findActiveByGrade(grade);
        if (gradePairs.isEmpty()) {
            return eligibilityResponse(applicationId, grade, "GRADE_NOT_COVERED", "Ninguna pareja activa cubre el curso del postulante", List.of());
        }

        List<InterviewerPairEntity> validMembers = gradePairs.stream().filter(this::hasActiveValidMembers).toList();
        if (validMembers.isEmpty()) {
            return eligibilityResponse(applicationId, grade, "PAIR_MEMBER_INACTIVE", "Las parejas del curso tienen integrantes inactivos o roles inválidos", List.of());
        }

        LocalDate date = parseDate(dateValue);
        LocalTime time = parseTime(timeValue);
        int duration = normalizeDuration(durationValue);
        List<InterviewerPairEntity> available = validMembers;
        if (date != null && time != null) {
            available = validMembers.stream().filter(pair -> isAvailable(pair, date, time, duration, null)).toList();
            if (available.isEmpty()) {
                boolean scheduleExists = validMembers.stream().anyMatch(pair -> hasSharedSchedule(pair, date, time, duration));
                return eligibilityResponse(applicationId, grade,
                    scheduleExists ? "PAIR_SCHEDULE_CONFLICT" : "NO_SHARED_AVAILABILITY",
                    scheduleExists ? "Las parejas compatibles ya tienen una entrevista que se cruza con ese bloque" : "No hay una pareja compatible con disponibilidad conjunta en ese bloque",
                    List.of());
            }
        }

        return eligibilityResponse(applicationId, grade, null, null, available.stream().map(this::toResponse).toList());
    }

    @Transactional
    public Map<String, Object> normalizeHistorical(boolean execute, String confirmation) {
        if (execute && !"NORMALIZE_CYCLE_DIRECTOR_INTERVIEWS".equals(confirmation)) {
            throw new InterviewerPairException("NORMALIZATION_CONFIRMATION_REQUIRED",
                "Para ejecutar debes enviar confirmation=NORMALIZE_CYCLE_DIRECTOR_INTERVIEWS");
        }
        List<Map<String, Object>> report = new java.util.ArrayList<>();
        List<InterviewEntity> changed = new java.util.ArrayList<>();
        interviewRepository.findAllByOrderByCreatedAtDesc().stream()
            .filter(interview -> "CYCLE_DIRECTOR".equals(interview.getInterviewType()))
            .filter(interview -> interview.getInterviewerPair() == null)
            .forEach(interview -> {
                String grade = normalizeGrade(interview.getApplication().getStudent() == null
                    ? null : interview.getApplication().getStudent().getGradeApplied());
                InterviewerPairEntity exactPair = grade == null ? null : pairRepository.findActiveByGrade(grade).stream()
                    .filter(pair -> interview.getInterviewer() != null && interview.getSecondInterviewer() != null)
                    .filter(pair -> pair.getCycleDirector().getId().equals(interview.getInterviewer().getId())
                        && pair.getPsychologist().getId().equals(interview.getSecondInterviewer().getId()))
                    .findFirst().orElse(null);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("interviewId", interview.getId());
                item.put("applicationId", interview.getApplication().getId());
                item.put("grade", grade);
                if (exactPair == null) {
                    item.put("action", "SKIP");
                    item.put("reason", grade == null
                        ? "La postulación no tiene un curso canónico"
                        : "No existe una pareja activa que coincida exactamente con los entrevistadores históricos");
                } else {
                    item.put("action", execute ? "LINKED" : "WOULD_LINK");
                    item.put("pairId", exactPair.getId());
                    item.put("reason", "Se conserva la entrevista y sus evaluaciones; solo se agrega el vínculo trazable");
                    if (execute) {
                        interview.setInterviewerPair(exactPair);
                        changed.add(interview);
                    }
                }
                report.add(item);
            });
        if (execute && !changed.isEmpty()) interviewRepository.saveAll(changed);
        long linkable = report.stream().filter(item -> !"SKIP".equals(item.get("action"))).count();
        return Map.of("success", true, "data", Map.of(
            "mode", execute ? "execute" : "dry-run",
            "candidates", report.size(),
            "linkable", linkable,
            "skipped", report.size() - linkable,
            "changed", changed.size(),
            "items", report
        ));
    }

    public InterviewerPairEntity requireEligiblePair(Long pairId, Long applicationId, LocalDate date, LocalTime time,
                                                      Integer duration, Long excludedInterviewId) {
        if (pairId == null) {
            throw new InterviewerPairException("PAIR_REQUIRED", "Selecciona una pareja de Director de Ciclo y Psicólogo");
        }
        InterviewerPairEntity pair = load(pairId);
        if (!pair.isActive()) {
            throw new InterviewerPairException("PAIR_INACTIVE", "La pareja seleccionada ya no está activa");
        }
        if (!hasActiveValidMembers(pair)) {
            throw new InterviewerPairException("PAIR_MEMBER_INACTIVE", "La pareja tiene integrantes inactivos o con roles inválidos");
        }
        var application = applicationRepository.findActiveById(applicationId)
            .orElseThrow(() -> new InterviewerPairException("APPLICATION_NOT_FOUND", "Postulación no encontrada"));
        String grade = normalizeGrade(application.getStudent() == null ? null : application.getStudent().getGradeApplied());
        if (grade == null) {
            throw new InterviewerPairException("GRADE_MISSING", "La postulación no tiene un curso válido");
        }
        if (!pair.getGrades().contains(grade)) {
            throw new InterviewerPairException("GRADE_NOT_COVERED", "La pareja no cubre el curso " + grade);
        }
        int normalizedDuration = normalizeDuration(duration);
        if (date == null || time == null || !hasSharedSchedule(pair, date, time, normalizedDuration)) {
            throw new InterviewerPairException("NO_SHARED_AVAILABILITY", "La pareja no tiene disponibilidad conjunta en el bloque seleccionado");
        }
        if (hasConflict(pair.getCycleDirector().getId(), date, time, normalizedDuration, excludedInterviewId)
            || hasConflict(pair.getPsychologist().getId(), date, time, normalizedDuration, excludedInterviewId)) {
            throw new InterviewerPairException("PAIR_SCHEDULE_CONFLICT", "Uno de los integrantes ya tiene una entrevista en ese bloque");
        }
        return pair;
    }

    public long countAvailablePairs(LocalDate date, LocalTime time, Integer duration) {
        return availablePairs(date, time, duration).size();
    }

    public List<Map<String, Object>> availablePairs(LocalDate date, LocalTime time, Integer duration) {
        return pairRepository.findByActiveTrueOrderByCycleDirectorFirstNameAscCycleDirectorLastNameAsc().stream()
            .filter(this::hasActiveValidMembers)
            .filter(pair -> isAvailable(pair, date, time, normalizeDuration(duration), null))
            .map(this::toResponse)
            .toList();
    }

    private InterviewerPairEntity buildPair(Map<String, Object> payload, InterviewerPairEntity previous) {
        Long directorId = number(payload.get("cycleDirectorId"));
        Long psychologistId = number(payload.get("psychologistId"));
        if (directorId == null || psychologistId == null) {
            throw new InterviewerPairException("PAIR_MEMBERS_REQUIRED", "Debes seleccionar un Director de Ciclo y un Psicólogo");
        }
        UserEntity director = requireMember(directorId, Role.CYCLE_DIRECTOR, "Director de Ciclo");
        UserEntity psychologist = requireMember(psychologistId, Role.PSYCHOLOGIST, "Psicólogo");
        if (directorId.equals(psychologistId)) {
            throw new InterviewerPairException("PAIR_MEMBERS_DUPLICATED", "Una persona no puede ocupar ambos roles");
        }
        ensureMemberAvailable(directorId, previous);
        ensureMemberAvailable(psychologistId, previous);

        Set<String> grades = normalizeGrades(payload.get("grades"));
        if (grades.isEmpty()) {
            throw new InterviewerPairException("PAIR_GRADES_REQUIRED", "Asigna al menos un curso a la pareja");
        }
        InterviewerPairEntity pair = new InterviewerPairEntity();
        pair.setCycleDirector(director);
        pair.setPsychologist(psychologist);
        pair.setGrades(grades);
        pair.setActive(true);
        return pair;
    }

    private void ensureMemberAvailable(Long userId, InterviewerPairEntity previous) {
        pairRepository.findActiveByMember(userId).ifPresent(existing -> {
            if (previous == null || !existing.getId().equals(previous.getId())) {
                throw new InterviewerPairException("PAIR_MEMBER_ALREADY_ASSIGNED", "El integrante ya pertenece a otra pareja activa");
            }
        });
    }

    private UserEntity requireMember(Long id, Role role, String label) {
        UserEntity user = userRepository.findById(id)
            .orElseThrow(() -> new InterviewerPairException("PAIR_MEMBER_NOT_FOUND", label + " no encontrado"));
        if (user.getRole() != role) {
            throw new InterviewerPairException("PAIR_MEMBER_ROLE_INVALID", label + " tiene un rol incompatible");
        }
        if (!user.isActive()) {
            throw new InterviewerPairException("PAIR_MEMBER_INACTIVE", label + " está inactivo");
        }
        return user;
    }

    private boolean hasActiveValidMembers(InterviewerPairEntity pair) {
        return pair.getCycleDirector().isActive()
            && pair.getCycleDirector().getRole() == Role.CYCLE_DIRECTOR
            && pair.getPsychologist().isActive()
            && pair.getPsychologist().getRole() == Role.PSYCHOLOGIST;
    }

    private boolean isAvailable(InterviewerPairEntity pair, LocalDate date, LocalTime time, int duration, Long excludedInterviewId) {
        return memberAvailable(pair.getCycleDirector().getId(), date, time, duration, excludedInterviewId)
            && memberAvailable(pair.getPsychologist().getId(), date, time, duration, excludedInterviewId);
    }

    private boolean memberAvailable(Long userId, LocalDate date, LocalTime time, int duration, Long excludedInterviewId) {
        return scheduleCovers(userId, date, time, duration)
            && !hasConflict(userId, date, time, duration, excludedInterviewId);
    }

    private boolean hasSharedSchedule(InterviewerPairEntity pair, LocalDate date, LocalTime time, int duration) {
        return scheduleCovers(pair.getCycleDirector().getId(), date, time, duration)
            && scheduleCovers(pair.getPsychologist().getId(), date, time, duration);
    }

    private boolean scheduleCovers(Long userId, LocalDate date, LocalTime time, int duration) {
        LocalTime end = time.plusMinutes(duration);
        List<InterviewerScheduleEntity> schedules = scheduleRepository.findAvailableTemplates(userId, date, date.getDayOfWeek().name(), date.getYear());
        return schedules.stream().anyMatch(schedule -> !time.isBefore(schedule.getStartTime()) && !end.isAfter(schedule.getEndTime()));
    }

    private boolean hasConflict(Long userId, LocalDate date, LocalTime time, int duration, Long excludedInterviewId) {
        LocalTime end = time.plusMinutes(duration);
        return interviewRepository.findBlockingForInterviewer(userId, date, NON_BLOCKING_STATUSES).stream()
            .filter(interview -> excludedInterviewId == null || !excludedInterviewId.equals(interview.getId()))
            .anyMatch(interview -> overlaps(time, end, interview));
    }

    private boolean overlaps(LocalTime start, LocalTime end, InterviewEntity interview) {
        if (interview.getScheduledTime() == null) return false;
        LocalTime interviewEnd = interview.getScheduledTime().plusMinutes(interview.getDuration() == null ? 60 : interview.getDuration());
        return start.isBefore(interviewEnd) && end.isAfter(interview.getScheduledTime());
    }

    private InterviewerPairEntity load(Long id) {
        return pairRepository.findById(id)
            .orElseThrow(() -> new InterviewerPairException("PAIR_NOT_FOUND", "Pareja no encontrada"));
    }

    private Map<String, Object> eligibilityResponse(Long applicationId, String grade, String reasonCode,
                                                    String reason, List<Map<String, Object>> pairs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", applicationId);
        data.put("grade", grade);
        data.put("eligiblePairs", pairs);
        data.put("count", pairs.size());
        data.put("reasonCode", reasonCode);
        data.put("reason", reason);
        return Map.of("success", true, "data", data);
    }

    private Map<String, Object> toResponse(InterviewerPairEntity pair) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", pair.getId());
        data.put("cycleDirector", memberResponse(pair.getCycleDirector()));
        data.put("psychologist", memberResponse(pair.getPsychologist()));
        data.put("grades", pair.getGrades().stream().sorted((a, b) -> Integer.compare(GRADE_CODES.indexOf(a), GRADE_CODES.indexOf(b))).toList());
        data.put("active", pair.isActive());
        data.put("revision", pair.getRevision());
        data.put("supersedesPairId", pair.getSupersedesPair() == null ? null : pair.getSupersedesPair().getId());
        data.put("createdAt", pair.getCreatedAt());
        data.put("archivedAt", pair.getArchivedAt());
        return data;
    }

    private Map<String, Object> memberResponse(UserEntity user) {
        return Map.of(
            "id", user.getId(),
            "name", (user.getFirstName() + " " + user.getLastName()).trim(),
            "role", user.getRole().name(),
            "active", user.isActive()
        );
    }

    private Map<String, Object> memberOptionResponse(UserEntity user) {
        Map<String, Object> data = new LinkedHashMap<>(memberResponse(user));
        data.put("activePairId", pairRepository.findActiveByMember(user.getId()).map(InterviewerPairEntity::getId).orElse(null));
        return data;
    }

    private List<Map<String, String>> gradeCatalog() {
        return GRADE_CODES.stream().map(code -> Map.of("code", code, "label", gradeLabel(code))).toList();
    }

    static String normalizeGrade(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT)
            .replace("Á", "A").replace("É", "E").replace("Í", "I").replace("Ó", "O").replace("Ú", "U")
            .replace("°", "").replace("º", "").replace('-', '_').replace(' ', '_')
            .replaceAll("_+", "_");
        normalized = switch (normalized) {
            case "PREKINDER", "PRE_KINDER", "PK" -> "PRE_KINDER";
            case "I_MEDIO" -> "1_MEDIO";
            case "II_MEDIO" -> "2_MEDIO";
            case "III_MEDIO" -> "3_MEDIO";
            case "IV_MEDIO" -> "4_MEDIO";
            default -> normalized;
        };
        return GRADE_CODES.contains(normalized) ? normalized : null;
    }

    private Set<String> normalizeGrades(Object raw) {
        if (!(raw instanceof Collection<?> values)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            String grade = normalizeGrade(String.valueOf(value));
            if (grade == null) {
                throw new InterviewerPairException("GRADE_INVALID", "Curso no reconocido: " + value);
            }
            result.add(grade);
        }
        return result;
    }

    private String gradeLabel(String code) {
        return switch (code) {
            case "PRE_KINDER" -> "Prekínder";
            case "KINDER" -> "Kínder";
            default -> code.replace("_BASICO", "° Básico").replace("1_MEDIO", "I Medio")
                .replace("2_MEDIO", "II Medio").replace("3_MEDIO", "III Medio").replace("4_MEDIO", "IV Medio");
        };
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private LocalTime parseTime(String value) {
        return value == null || value.isBlank() ? null : LocalTime.parse(value);
    }

    private int normalizeDuration(Integer value) {
        return Math.max(15, Math.min(value == null ? 60 : value, 240));
    }
}
