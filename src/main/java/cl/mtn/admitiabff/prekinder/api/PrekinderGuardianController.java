package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderGuardianService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prekinder")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderGuardianController {
    private final PrekinderGuardianService guardian;

    public PrekinderGuardianController(PrekinderGuardianService guardian) { this.guardian = guardian; }

    @GetMapping("/me/applications")
    public Map<String, Object> applications() {
        return Map.of("success", true, "data", guardian.myApplications());
    }
}
