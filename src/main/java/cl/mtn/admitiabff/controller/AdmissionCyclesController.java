package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.AdmissionCycleService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admission-cycles")
public class AdmissionCyclesController {
    private final AdmissionCycleService admissionCycleService;

    public AdmissionCyclesController(AdmissionCycleService admissionCycleService) {
        this.admissionCycleService = admissionCycleService;
    }

    @GetMapping("/current")
    public Map<String, Object> current() {
        return admissionCycleService.current();
    }

    @PostMapping("/{academicYear}/close")
    public ResponseEntity<Map<String, Object>> close(
            @PathVariable Integer academicYear,
            @RequestBody Map<String, Object> payload) {
        String confirmationText = payload.get("confirmationText") == null
                ? null : String.valueOf(payload.get("confirmationText"));
        return ResponseEntity.accepted().body(admissionCycleService.close(academicYear, confirmationText));
    }

    @PostMapping("/{academicYear}/retry-failed")
    public ResponseEntity<Map<String, Object>> retryFailed(@PathVariable Integer academicYear) {
        return ResponseEntity.accepted().body(admissionCycleService.retryFailed(academicYear));
    }
}
