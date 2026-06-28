package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.AuthService;
import cl.mtn.admitiabff.service.GradeAvailabilityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class GradeAvailabilityController {

    private final GradeAvailabilityService service;
    private final AuthService authService;

    public GradeAvailabilityController(GradeAvailabilityService service, AuthService authService) {
        this.service = service;
        this.authService = authService;
    }

    @GetMapping("/grade-availability")
    public ResponseEntity<Map<String, Object>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @PutMapping("/grade-availability")
    public ResponseEntity<Map<String, Object>> bulkUpdate(@RequestBody Map<String, Object> payload) {
        AuthService.AuthContextHolder auth = authService.requireAuth();
        List<?> updatesRaw = (List<?>) payload.getOrDefault("updates", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> updates = (List<Map<String, Object>>) updatesRaw;
        return ResponseEntity.ok(service.bulkUpdate(updates, auth.email()));
    }

    @GetMapping("/public/grade-availability")
    public ResponseEntity<Map<String, Object>> getAvailable() {
        return ResponseEntity.ok(service.getAvailable());
    }
}
