package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.grade.GradeAvailabilityEntity;
import cl.mtn.admitiabff.repository.GradeAvailabilityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class GradeAvailabilityService {

    private final GradeAvailabilityRepository repository;

    public GradeAvailabilityService(GradeAvailabilityRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> getAll() {
        List<Map<String, Object>> data = repository.findAll().stream()
            .map(this::toResponse)
            .toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    public Map<String, Object> getAvailable() {
        List<Map<String, Object>> data = repository.findAll().stream()
            .map(entity -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("gradeLevel", entity.getGradeLevel());
                map.put("hasVacancyM", entity.getHasVacancyM());
                map.put("hasVacancyF", entity.getHasVacancyF());
                return map;
            })
            .toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    @Transactional
    public Map<String, Object> bulkUpdate(List<Map<String, Object>> updates, String updatedBy) {
        for (Map<String, Object> update : updates) {
            String gradeLevel = String.valueOf(update.get("gradeLevel"));
            Boolean hasVacancyM = (Boolean) update.get("hasVacancyM");
            Boolean hasVacancyF = (Boolean) update.get("hasVacancyF");

            repository.findByGradeLevel(gradeLevel).ifPresent(entity -> {
                if (hasVacancyM != null) entity.setHasVacancyM(hasVacancyM);
                if (hasVacancyF != null) entity.setHasVacancyF(hasVacancyF);
                entity.setUpdatedBy(updatedBy);
                repository.save(entity);
            });
        }

        List<Map<String, Object>> data = repository.findAll().stream()
            .map(this::toResponse)
            .toList();
        return Map.of("success", true, "message", "Disponibilidad de vacantes actualizada correctamente", "data", data);
    }

    public boolean hasVacancy(String gradeLevel, String gender) {
        if (gender == null) return false;
        return switch (gender) {
            case "MALE" -> repository.existsByGradeLevelAndHasVacancyMTrue(gradeLevel);
            case "FEMALE" -> repository.existsByGradeLevelAndHasVacancyFTrue(gradeLevel);
            default -> false;
        };
    }

    private Map<String, Object> toResponse(GradeAvailabilityEntity entity) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entity.getId());
        response.put("gradeLevel", entity.getGradeLevel());
        response.put("hasVacancyM", entity.getHasVacancyM());
        response.put("hasVacancyF", entity.getHasVacancyF());
        response.put("updatedAt", entity.getUpdatedAt());
        response.put("updatedBy", entity.getUpdatedBy());
        return response;
    }
}
