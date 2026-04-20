package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.InterviewerScheduleRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.util.JsonSupport;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {
    private static final List<Role> EVALUATOR_ROLES = List.of(Role.TEACHER, Role.PSYCHOLOGIST, Role.CYCLE_DIRECTOR, Role.COORDINATOR, Role.INTERVIEWER);

    private final UserRepository userRepository;
    private final EvaluationRepository evaluationRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewerScheduleRepository interviewerScheduleRepository;
    private final AuthService authService;
    private final JsonSupport jsonSupport;

    public UserService(UserRepository userRepository, EvaluationRepository evaluationRepository, InterviewRepository interviewRepository, InterviewerScheduleRepository interviewerScheduleRepository, AuthService authService, JsonSupport jsonSupport) {
        this.userRepository = userRepository;
        this.evaluationRepository = evaluationRepository;
        this.interviewRepository = interviewRepository;
        this.interviewerScheduleRepository = interviewerScheduleRepository;
        this.authService = authService;
        this.jsonSupport = jsonSupport;
    }

    public Map<String, Object> roles() {
        return Map.of("roles", Arrays.stream(Role.values()).map(Enum::name).toList());
    }

    public Map<String, Object> publicSchoolStaff(Boolean activeOnly) {
        List<Map<String, Object>> staff = userRepository.findByRoleInOrderByRoleAscFirstNameAscLastNameAsc(
                Arrays.stream(Role.values()).filter(role -> role != Role.APODERADO).toList())
            .stream()
            .filter(user -> activeOnly == null || !activeOnly || user.isActive())
            .map(this::toResponse)
            .toList();
        return Map.of("success", true, "data", staff, "content", staff, "count", staff.size(), "totalElements", staff.size());
    }

    public Map<String, Object> me() {
        return Map.of("success", true, "user", toResponse(authService.requireAuthenticatedUser()));
    }

    public Map<String, Object> stats() {
        long total = userRepository.count();
        long active = userRepository.countByActiveTrue();
        Map<String, Object> byRole = userRepository.countByRole().stream().collect(Collectors.toMap(item -> item.getRole().name(), UserRepository.RoleCountView::getTotal, (a, b) -> b, LinkedHashMap::new));
        return Map.of("success", true, "data", Map.of("total", total, "active", active, "inactive", total - active, "byRole", byRole));
    }

    public Map<String, Object> statistics() {
        Map<String, Object> stats = (Map<String, Object>) this.stats().get("data");
        long total = ((Number) stats.get("total")).longValue();
        long active = ((Number) stats.get("active")).longValue();
        return Map.of("success", true, "data", Map.of("totalUsers", total, "activeUsers", active, "inactiveUsers", total - active, "usersByRole", stats.get("byRole"), "activationRate", total == 0 ? 0 : (active * 100.0) / total));
    }

    public Map<String, Object> byRole(String role, Boolean activeOnly) {
        Role normalized = Role.valueOf(role.toUpperCase());
        List<Map<String, Object>> users = userRepository.findByRoleOrderByFirstNameAscLastNameAsc(normalized).stream()
            .filter(user -> activeOnly == null || !activeOnly || user.isActive())
            .map(this::toResponse)
            .toList();
        return Map.of("success", true, "data", users, "count", users.size(), "role", normalized.name());
    }

    public Map<String, Object> evaluators(String subject, Boolean activeOnly) {
        List<Map<String, Object>> users = userRepository.findByRoleInOrderByRoleAscFirstNameAscLastNameAsc(EVALUATOR_ROLES).stream()
            .filter(user -> subject == null || subject.isBlank() || subject.equals(user.getSubject()))
            .filter(user -> activeOnly == null || !activeOnly || user.isActive())
            .map(this::toResponse)
            .toList();
        return Map.of("success", true, "data", users, "count", users.size());
    }

    public Map<String, Object> guardians(int page, int size, String search, Boolean active) {
        return pageUsers(page, size, search, active, Role.APODERADO, true);
    }

    public Map<String, Object> staff(int page, int size, String search, String role, Boolean active) {
        return pageUsers(page, size, search, active, role == null || role.isBlank() ? null : Role.valueOf(role.toUpperCase()), false);
    }

    public Map<String, Object> search(String query, String role, Boolean activeOnly) {
        Pageable pageable = PageRequest.of(0, 50);
        List<Map<String, Object>> users = userRepository.search(query, pageable).stream()
            .filter(user -> role == null || role.isBlank() || user.getRole().name().equalsIgnoreCase(role))
            .filter(user -> activeOnly == null || !activeOnly || user.isActive())
            .map(this::toResponse)
            .toList();
        return Map.of("success", true, "data", users, "count", users.size(), "query", query);
    }

    public Map<String, Object> associatedData(Long id) {
        long evaluations = evaluationRepository.findByEvaluatorIdOrderByCreatedAtDesc(id).size();
        long interviews = interviewRepository.findVisibleForInterviewer(id, List.of()).size();
        long schedules = interviewerScheduleRepository.findByInterviewerIdOrderByYearDescDayOfWeekAscStartTimeAsc(id).size();
        return Map.of("success", true, "data", Map.of("evaluations", evaluations, "interviews", interviews, "schedules", schedules));
    }

    public Map<String, Object> get(Long id) {
        return toResponse(userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado")));
    }

    public Map<String, Object> getAll() {
        List<Map<String, Object>> users = userRepository.findAll().stream().map(this::toResponse).toList();
        return Map.of("success", true, "data", users, "users", users, "count", users.size());
    }

    public Map<String, Object> cacheStats() {
        return Map.of("success", true, "data", Map.of("strategy", "jpa", "hits", 0, "misses", 0));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        UserEntity user = new UserEntity();
        merge(user, payload, true);
        UserEntity saved = userRepository.save(user);
        return Map.of("success", true, "message", "Usuario creado correctamente", "data", Map.of("id", saved.getId(), "first_name", saved.getFirstName(), "last_name", saved.getLastName(), "email", saved.getEmail(), "role", saved.getRole().name()));
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> payload) {
        UserEntity user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        merge(user, payload, false);
        return Map.of("success", true, "message", "Usuario actualizado correctamente", "data", toResponse(userRepository.save(user)));
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        if (evaluationRepository.findByEvaluatorIdOrderByCreatedAtDesc(id).size() > 0 || interviewRepository.findVisibleForInterviewer(id, List.of()).size() > 0 || interviewerScheduleRepository.findByInterviewerIdOrderByYearDescDayOfWeekAscStartTimeAsc(id).size() > 0) {
            throw new IllegalArgumentException("No se puede eliminar el usuario porque tiene datos asociados");
        }
        userRepository.deleteById(id);
        return Map.of("success", true, "message", "Usuario eliminado correctamente");
    }

    @Transactional
    public Map<String, Object> status(Long id, boolean active) {
        UserEntity user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        user.setActive(active);
        return Map.of("success", true, "message", active ? "Usuario activado" : "Usuario desactivado", "data", toResponse(userRepository.save(user)));
    }

    @Transactional
    public Map<String, Object> resetPassword(Long id) {
        UserEntity user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        String temporaryPassword = "Tmp-" + UUID.randomUUID().toString().substring(0, 8);
        user.setPasswordHash(authService.hashPassword(temporaryPassword));
        userRepository.save(user);
        return Map.of("success", true, "message", "Contraseña temporal generada", "data", Map.of("temporaryPassword", temporaryPassword));
    }

    @Transactional
    public Map<String, Object> verifyEmail(Long id) {
        UserEntity user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        user.setEmailVerified(true);
        userRepository.save(user);
        return Map.of("success", true, "message", "Email verificado", "data", Map.of("id", user.getId(), "email", user.getEmail(), "email_verified", user.isEmailVerified()));
    }

    @Transactional
    public Map<String, Object> preferences(Long id, Map<String, Object> payload) {
        UserEntity user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        user.setPreferencesJson(jsonSupport.write(payload.getOrDefault("preferences", payload)));
        return Map.of("success", true, "message", "Preferencias actualizadas", "data", toResponse(userRepository.save(user)));
    }

    private Map<String, Object> pageUsers(int page, int size, String search, Boolean active, Role role, boolean guardiansOnly) {
        Pageable pageable = PageRequest.of(page, size);
        Specification<UserEntity> specification = Specification.where((root, query, cb) -> guardiansOnly ? cb.equal(root.get("role"), Role.APODERADO) : cb.notEqual(root.get("role"), Role.APODERADO));
        if (role != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("role"), role));
        }
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.toLowerCase() + "%";
            specification = specification.and((root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("firstName")), pattern),
                cb.like(cb.lower(root.get("lastName")), pattern),
                cb.like(cb.lower(root.get("email")), pattern),
                cb.like(cb.lower(cb.coalesce(root.get("rut"), "")), pattern)
            ));
        }
        if (active != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        Page<UserEntity> result = userRepository.findAll(specification, pageable);
        return pageResponse(result.map(this::toResponse));
    }

    private void merge(UserEntity user, Map<String, Object> payload, boolean creating) {
        user.setFirstName(stringValue(payload.getOrDefault("firstName", user.getFirstName())));
        user.setLastName(stringValue(payload.getOrDefault("lastName", user.getLastName())));
        user.setEmail(stringValue(payload.getOrDefault("email", user.getEmail())).toLowerCase());
        user.setRole(payload.containsKey("role") ? Role.valueOf(String.valueOf(payload.get("role"))) : user.getRole() == null ? Role.APODERADO : user.getRole());
        user.setRut(stringValue(payload.getOrDefault("rut", user.getRut())));
        user.setPhone(stringValue(payload.getOrDefault("phone", user.getPhone())));
        user.setSubject(stringValue(payload.getOrDefault("subject", user.getSubject())));
        user.setEducationalLevel(stringValue(payload.getOrDefault("educationalLevel", user.getEducationalLevel())));
        user.setActive(Boolean.parseBoolean(String.valueOf(payload.getOrDefault("active", user.isActive() || creating))));
        user.setEmailVerified(Boolean.parseBoolean(String.valueOf(payload.getOrDefault("emailVerified", user.isEmailVerified()))));
        Object password = payload.get("password");
        if (creating || (password != null && !String.valueOf(password).isBlank())) {
            user.setPasswordHash(authService.hashPassword(password == null || String.valueOf(password).isBlank() ? UUID.randomUUID().toString() : String.valueOf(password)));
        }
        if (creating && user.getPreferencesJson() == null) {
            user.setPreferencesJson(jsonSupport.write(Map.of()));
        }
    }

    private Map<String, Object> toResponse(UserEntity user) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", user.getId());
        response.put("firstName", user.getFirstName());
        response.put("lastName", user.getLastName());
        response.put("fullName", user.getFirstName() + " " + user.getLastName());
        response.put("email", user.getEmail());
        response.put("role", user.getRole().name());
        response.put("subject", user.getSubject());
        response.put("educationalLevel", user.getEducationalLevel());
        response.put("rut", user.getRut());
        response.put("phone", user.getPhone());
        response.put("active", user.isActive());
        response.put("emailVerified", user.isEmailVerified());
        response.put("canInterview", EVALUATOR_ROLES.contains(user.getRole()));
        response.put("createdAt", user.getCreatedAt());
        return response;
    }

    private Map<String, Object> pageResponse(Page<Map<String, Object>> page) {
        return Map.of("content", page.getContent(), "number", page.getNumber(), "size", page.getSize(), "totalElements", page.getTotalElements(), "totalPages", page.getTotalPages(), "first", page.isFirst(), "last", page.isLast(), "numberOfElements", page.getNumberOfElements(), "empty", page.isEmpty());
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
