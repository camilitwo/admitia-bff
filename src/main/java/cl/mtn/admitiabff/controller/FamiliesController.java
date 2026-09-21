package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.FamilyService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/families")
public class FamiliesController {
    private final FamilyService families;

    public FamiliesController(FamilyService families) { this.families = families; }

    @GetMapping("/applications/{applicationId}/candidates")
    public Map<String, Object> candidates(@PathVariable long applicationId) {
        return families.candidates(applicationId);
    }

    @PostMapping("/applications/{applicationId}/association")
    public Map<String, Object> associate(@PathVariable long applicationId, @RequestBody Map<String, Object> payload) {
        return families.confirmAssociation(applicationId, Long.parseLong(String.valueOf(payload.get("familyId"))));
    }

    @GetMapping("/{familyId}/processes/{academicYear}/form")
    public Map<String, Object> form(@PathVariable long familyId, @PathVariable int academicYear) {
        return families.form(familyId, academicYear);
    }
}
