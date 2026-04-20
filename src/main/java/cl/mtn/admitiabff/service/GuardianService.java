package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.person.GuardianEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.GuardianRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class GuardianService {
    private final GuardianRepository guardianRepository;
    private final UserRepository userRepository;
    private final AuthService authService;

    public GuardianService(GuardianRepository guardianRepository, UserRepository userRepository, AuthService authService) {
        this.guardianRepository = guardianRepository;
        this.userRepository = userRepository;
        this.authService = authService;
    }

    public Map<String, Object> getAll(String relationship, String search, int page, int limit) {
        Page<GuardianEntity> result = guardianRepository.search(emptyToNull(relationship), emptyToNull(search), PageRequest.of(page, limit));
        return pageResponse(result.map(this::toResponse));
    }

    public Map<String, Object> stats() {
        long total = guardianRepository.count();
        long linkedUsers = guardianRepository.countByUserIsNotNull();
        return Map.of("success", true, "data", Map.of("total", total, "linkedUsers", linkedUsers, "standalone", total - linkedUsers));
    }

    public Map<String, Object> byId(Long id) {
        GuardianEntity entity = guardianRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Apoderado no encontrado"));
        enforceAccess(entity);
        return toResponse(entity);
    }

    public Map<String, Object> byRut(String rut) {
        return toResponse(guardianRepository.findByRut(rut).orElseThrow(() -> new IllegalArgumentException("Apoderado no encontrado")));
    }

    public List<Map<String, Object>> byUser(Long userId) {
        enforceAccess(userId);
        return guardianRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        GuardianEntity entity = new GuardianEntity();
        merge(entity, payload);
        return Map.of("success", true, "message", "Apoderado creado correctamente", "data", toResponse(guardianRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> payload) {
        GuardianEntity entity = guardianRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Apoderado no encontrado"));
        enforceAccess(entity);
        merge(entity, payload);
        return Map.of("success", true, "message", "Apoderado actualizado correctamente", "data", toResponse(guardianRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        guardianRepository.deleteById(id);
        return Map.of("success", true, "message", "Apoderado eliminado correctamente");
    }

    private void merge(GuardianEntity entity, Map<String, Object> payload) {
        entity.setFullName(value(payload.getOrDefault("fullName", entity.getFullName())));
        entity.setRut(value(payload.getOrDefault("rut", entity.getRut())));
        entity.setEmail(value(payload.getOrDefault("email", entity.getEmail())));
        entity.setPhone(value(payload.getOrDefault("phone", entity.getPhone())));
        entity.setRelationship(value(payload.getOrDefault("relationship", entity.getRelationship())));
        entity.setAddress(value(payload.getOrDefault("address", entity.getAddress())));
        entity.setProfession(value(payload.getOrDefault("profession", entity.getProfession())));
        entity.setWorkplace(value(payload.getOrDefault("workplace", entity.getWorkplace())));
        if (payload.get("userId") instanceof Number number) {
            UserEntity user = userRepository.findById(number.longValue()).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
            entity.setUser(user);
        }
    }

    private void enforceAccess(GuardianEntity entity) {
        enforceAccess(entity.getUser() == null ? null : entity.getUser().getId());
    }

    private void enforceAccess(Long userId) {
        AuthService.AuthContextHolder auth = authService.requireAuth();
        if ("ADMIN".equals(auth.role()) || "COORDINATOR".equals(auth.role())) {
            return;
        }
        if (!Role.APODERADO.name().equals(auth.role()) || userId == null || !userId.equals(auth.id())) {
            throw new IllegalArgumentException("Sin permisos para acceder al apoderado");
        }
    }

    private Map<String, Object> toResponse(GuardianEntity entity) {
        String[] parts = entity.getFullName() == null ? new String[]{"", ""} : entity.getFullName().split(" ", 2);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entity.getId());
        response.put("userId", entity.getUser() == null ? null : entity.getUser().getId());
        response.put("firstName", parts.length > 0 ? parts[0] : "");
        response.put("lastName", parts.length > 1 ? parts[1] : "");
        response.put("fullName", entity.getFullName());
        response.put("rut", entity.getRut());
        response.put("email", entity.getEmail());
        response.put("phone", entity.getPhone());
        response.put("relationship", entity.getRelationship());
        response.put("address", entity.getAddress());
        response.put("profession", entity.getProfession());
        response.put("workplace", entity.getWorkplace());
        response.put("createdAt", entity.getCreatedAt());
        response.put("updatedAt", entity.getUpdatedAt());
        return response;
    }

    private Map<String, Object> pageResponse(Page<Map<String, Object>> page) {
        return Map.of("content", page.getContent(), "number", page.getNumber(), "size", page.getSize(), "totalElements", page.getTotalElements(), "totalPages", page.getTotalPages(), "first", page.isFirst(), "last", page.isLast(), "numberOfElements", page.getNumberOfElements(), "empty", page.isEmpty());
    }

    private String value(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
