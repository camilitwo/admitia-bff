package cl.mtn.admitiabff.controller;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "admitia-bff", "timestamp", Instant.now().toString());
    }

    @GetMapping("/ready")
    public Map<String, Object> ready() {
        return Map.of("status", "READY", "services", Map.of("database", "configured", "mail", "embedded"), "timestamp", Instant.now().toString());
    }

    @GetMapping("/gateway/status")
    public Map<String, Object> gatewayStatus() {
        return Map.of("success", true, "version", "java-monolith", "features", java.util.List.of("jwt", "csrf-token", "file-upload", "analytics"), "baseUrl", "http://localhost:8080", "timestamp", Instant.now().toString());
    }
}
