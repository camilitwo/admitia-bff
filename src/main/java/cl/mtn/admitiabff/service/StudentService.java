package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.StudentRepository;
import cl.mtn.admitiabff.util.RutUtils;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StudentService {
    private final StudentRepository studentRepository;
    private final ApplicationRepository applicationRepository;

    public StudentService(StudentRepository studentRepository, ApplicationRepository applicationRepository) {
        this.studentRepository = studentRepository;
        this.applicationRepository = applicationRepository;
    }

    public Map<String, Object> validateRut(Map<String, Object> payload) {
        String rut = String.valueOf(payload.getOrDefault("rut", ""));
        return Map.of("success", true, "data", Map.of("rut", rut, "isValid", RutUtils.isValid(rut)));
    }

    public Map<String, Object> statisticsByGrade() {
        List<Map<String, Object>> data = studentRepository.countByGrade().stream().map(item -> Map.of("grade", item.getGrade(), "count", item.getTotal())).toList();
        return Map.of("success", true, "data", data);
    }

    public Map<String, Object> search(String term) {
        List<Map<String, Object>> data = studentRepository.search(term, PageRequest.of(0, 50)).stream().map(this::toResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    public Map<String, Object> byGrade(String grade) {
        List<Map<String, Object>> data = studentRepository.findByGradeAppliedOrderByFirstNameAscPaternalLastNameAsc(grade).stream().map(this::toResponse).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    public Map<String, Object> byRut(String rut) {
        return toResponse(studentRepository.findByRut(rut).orElseThrow(() -> new IllegalArgumentException("Estudiante no encontrado")));
    }

    public Map<String, Object> byGuardian(Long guardianId) {
        List<Map<String, Object>> data = applicationRepository.findAll().stream()
            .filter(application -> application.getDeletedAt() == null && application.getGuardian() != null && guardianId.equals(application.getGuardian().getId()))
            .map(application -> toResponse(application.getStudent()))
            .distinct()
            .toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    public Map<String, Object> getAll(Integer page, Integer size, String gradeApplied, String search) {
        Page<StudentEntity> result = studentRepository.search(emptyToNull(gradeApplied), emptyToNull(search), PageRequest.of(page == null ? 0 : page, size == null ? 15 : size));
        return pageResponse(result.map(this::toResponse));
    }

    public Map<String, Object> get(Long id) {
        return toResponse(studentRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Estudiante no encontrado")));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        StudentEntity entity = new StudentEntity();
        merge(entity, payload);
        return Map.of("success", true, "message", "Estudiante creado correctamente", "data", toResponse(studentRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> payload) {
        StudentEntity entity = studentRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Estudiante no encontrado"));
        merge(entity, payload);
        return Map.of("success", true, "message", "Estudiante actualizado correctamente", "data", toResponse(studentRepository.save(entity)));
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        studentRepository.deleteById(id);
        return Map.of("success", true, "message", "Estudiante eliminado correctamente");
    }

    private void merge(StudentEntity entity, Map<String, Object> payload) {
        entity.setFirstName(stringValue(payload.getOrDefault("firstName", entity.getFirstName())));
        entity.setPaternalLastName(stringValue(payload.getOrDefault("paternalLastName", entity.getPaternalLastName())));
        entity.setMaternalLastName(stringValue(payload.getOrDefault("maternalLastName", entity.getMaternalLastName())));
        entity.setRut(stringValue(payload.getOrDefault("rut", entity.getRut())));
        entity.setBirthDate(parseDate(payload.getOrDefault("birthDate", entity.getBirthDate())));
        entity.setEmail(stringValue(payload.getOrDefault("email", entity.getEmail())));
        entity.setAddress(stringValue(payload.getOrDefault("address", entity.getAddress())));
        entity.setGradeApplied(stringValue(payload.getOrDefault("gradeApplied", entity.getGradeApplied())));
        entity.setTargetSchool(stringValue(payload.getOrDefault("targetSchool", entity.getTargetSchool())));
        entity.setCurrentSchool(stringValue(payload.getOrDefault("currentSchool", entity.getCurrentSchool())));
        entity.setSpecialNeeds(booleanValue(payload.getOrDefault("specialNeeds", entity.isSpecialNeeds())));
        entity.setSpecialNeedsDescription(stringValue(payload.getOrDefault("specialNeedsDescription", entity.getSpecialNeedsDescription())));
        entity.setAdditionalNotes(stringValue(payload.getOrDefault("additionalNotes", entity.getAdditionalNotes())));
        entity.setEmployeeChild(booleanValue(payload.getOrDefault("isEmployeeChild", entity.isEmployeeChild())));
        entity.setEmployeeParentName(stringValue(payload.getOrDefault("employeeParentName", entity.getEmployeeParentName())));
        entity.setAlumniChild(booleanValue(payload.getOrDefault("isAlumniChild", entity.isAlumniChild())));
        entity.setAlumniParentYear(integerValue(payload.getOrDefault("alumniParentYear", entity.getAlumniParentYear())));
        entity.setInclusionStudent(booleanValue(payload.getOrDefault("isInclusionStudent", entity.isInclusionStudent())));
        entity.setInclusionType(stringValue(payload.getOrDefault("inclusionType", entity.getInclusionType())));
        entity.setInclusionNotes(stringValue(payload.getOrDefault("inclusionNotes", entity.getInclusionNotes())));
    }

    private Map<String, Object> toResponse(StudentEntity entity) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entity.getId());
        response.put("firstName", entity.getFirstName());
        response.put("lastName", (safe(entity.getPaternalLastName()) + " " + safe(entity.getMaternalLastName())).trim());
        response.put("rut", entity.getRut());
        response.put("birthDate", entity.getBirthDate());
        response.put("gradeApplying", entity.getGradeApplied());
        response.put("currentSchool", entity.getCurrentSchool());
        response.put("specialNeeds", entity.isSpecialNeeds());
        response.put("specialNeedsDescription", entity.getSpecialNeedsDescription());
        response.put("age", entity.getAge());
        response.put("targetSchool", entity.getTargetSchool());
        response.put("isEmployeeChild", entity.isEmployeeChild());
        response.put("employeeParentName", entity.getEmployeeParentName());
        response.put("isAlumniChild", entity.isAlumniChild());
        response.put("alumniParentYear", entity.getAlumniParentYear());
        response.put("isInclusionStudent", entity.isInclusionStudent());
        response.put("inclusionType", entity.getInclusionType());
        response.put("inclusionNotes", entity.getInclusionNotes());
        return response;
    }

    private Map<String, Object> pageResponse(Page<Map<String, Object>> page) {
        return Map.of("content", page.getContent(), "number", page.getNumber(), "size", page.getSize(), "totalElements", page.getTotalElements(), "totalPages", page.getTotalPages(), "first", page.isFirst(), "last", page.isLast(), "numberOfElements", page.getNumberOfElements(), "empty", page.isEmpty());
    }

    private LocalDate parseDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate date) return date;
        if (String.valueOf(value).isBlank()) return null;
        return LocalDate.parse(String.valueOf(value));
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Integer integerValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }
    private String safe(String value) { return value == null ? "" : value; }
    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
