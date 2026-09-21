package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class FamilyService {
    private final JdbcTemplate jdbc;
    private final ApplicationRepository applications;
    private final AuthService auth;
    private final ApplicationService applicationService;

    public FamilyService(JdbcTemplate jdbc, ApplicationRepository applications, AuthService auth,
                         ApplicationService applicationService) {
        this.jdbc = jdbc;
        this.applications = applications;
        this.auth = auth;
        this.applicationService = applicationService;
    }

    public Map<String, Object> candidates(long applicationId) {
        ApplicationEntity application = ownedApplication(applicationId);
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT DISTINCT candidate.family_id,
                   (SELECT count(*) FROM applications sibling
                     WHERE sibling.family_id = candidate.family_id AND sibling.deleted_at IS NULL) AS applicant_count,
                   (SELECT string_agg(student.first_name, ', ' ORDER BY student.first_name)
                      FROM applications sibling JOIN students student ON student.id = sibling.student_id
                     WHERE sibling.family_id = candidate.family_id AND sibling.deleted_at IS NULL) AS first_names
              FROM applications candidate
              LEFT JOIN parents candidate_father ON candidate_father.id = candidate.father_id
              LEFT JOIN parents candidate_mother ON candidate_mother.id = candidate.mother_id
              LEFT JOIN parents source_father ON source_father.id = ?
              LEFT JOIN parents source_mother ON source_mother.id = ?
             WHERE candidate.family_id <> ? AND candidate.deleted_at IS NULL
               AND (
                 NULLIF(regexp_replace(coalesce(candidate_father.rut,''), '[^0-9kK]', '', 'g'), '') IN
                   (regexp_replace(coalesce(source_father.rut,''), '[^0-9kK]', '', 'g'), regexp_replace(coalesce(source_mother.rut,''), '[^0-9kK]', '', 'g'))
                 OR NULLIF(regexp_replace(coalesce(candidate_mother.rut,''), '[^0-9kK]', '', 'g'), '') IN
                   (regexp_replace(coalesce(source_father.rut,''), '[^0-9kK]', '', 'g'), regexp_replace(coalesce(source_mother.rut,''), '[^0-9kK]', '', 'g'))
               )
            """, (rs, row) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("familyId", rs.getLong("family_id"));
                item.put("applicantCount", rs.getInt("applicant_count"));
                item.put("applicantFirstNames", rs.getString("first_names"));
                return item;
            }, application.getFather() == null ? null : application.getFather().getId(),
               application.getMother() == null ? null : application.getMother().getId(), application.getFamily().getId());
        return Map.of("success", true, "data", rows);
    }

    @Transactional
    public Map<String, Object> confirmAssociation(long applicationId, long familyId) {
        ApplicationEntity application = ownedApplication(applicationId);
        Integer matches = jdbc.queryForObject("""
            SELECT count(*) FROM applications candidate
              LEFT JOIN parents candidate_father ON candidate_father.id = candidate.father_id
              LEFT JOIN parents candidate_mother ON candidate_mother.id = candidate.mother_id
              LEFT JOIN parents source_father ON source_father.id = ?
              LEFT JOIN parents source_mother ON source_mother.id = ?
             WHERE candidate.family_id = ? AND candidate.deleted_at IS NULL
               AND (
                 NULLIF(regexp_replace(coalesce(candidate_father.rut,''), '[^0-9kK]', '', 'g'), '') IN
                   (regexp_replace(coalesce(source_father.rut,''), '[^0-9kK]', '', 'g'), regexp_replace(coalesce(source_mother.rut,''), '[^0-9kK]', '', 'g'))
                 OR NULLIF(regexp_replace(coalesce(candidate_mother.rut,''), '[^0-9kK]', '', 'g'), '') IN
                   (regexp_replace(coalesce(source_father.rut,''), '[^0-9kK]', '', 'g'), regexp_replace(coalesce(source_mother.rut,''), '[^0-9kK]', '', 'g'))
               )
            """, Integer.class, application.getFather() == null ? null : application.getFather().getId(),
            application.getMother() == null ? null : application.getMother().getId(), familyId);
        if (matches == null || matches == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "La familia ya no coincide con los RUT informados");
        String sourceProcess = "GENERAL:" + application.getAcademicYear();
        Integer sourceForm = jdbc.queryForObject("SELECT count(*) FROM complementary_forms WHERE family_id = ? AND process_key = ?",
            Integer.class, application.getFamily().getId(), sourceProcess);
        if (sourceForm != null && sourceForm > 0) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "La postulación ya tiene un formulario familiar; la asociación requiere revisión administrativa");
        long previousFamilyId = application.getFamily().getId();
        jdbc.update("UPDATE applications SET family_id = ? WHERE id = ?", familyId, applicationId);
        jdbc.update("INSERT INTO family_members(family_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", familyId, auth.requireAuth().id());
        jdbc.update("DELETE FROM families family WHERE family.id = ? AND NOT EXISTS (SELECT 1 FROM applications WHERE family_id = family.id)", previousFamilyId);
        return Map.of("success", true, "familyId", familyId, "applicationId", applicationId);
    }

    public Map<String, Object> form(long familyId, int academicYear) {
        assertMember(familyId);
        ApplicationEntity representative = applications
            .findByFamilyIdAndAcademicYearAndDeletedAtIsNullOrderByCreatedAtAsc(familyId, academicYear).stream()
            .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Familia sin postulaciones en el proceso"));
        return applicationService.complementaryForm(representative.getId());
    }

    private ApplicationEntity ownedApplication(long applicationId) {
        ApplicationEntity application = applications.findActiveById(applicationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Postulación no encontrada"));
        assertMember(application.getFamily().getId());
        return application;
    }

    private void assertMember(long familyId) {
        AuthService.AuthContextHolder actor = auth.requireAuth();
        if (auth.hasAnyRoleContext(actor, Role.ADMIN, Role.COORDINATOR, Role.CYCLE_DIRECTOR)) return;
        Integer count = jdbc.queryForObject("SELECT count(*) FROM family_members WHERE family_id = ? AND user_id = ?",
            Integer.class, familyId, actor.id());
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No pertenece al grupo familiar");
    }
}
