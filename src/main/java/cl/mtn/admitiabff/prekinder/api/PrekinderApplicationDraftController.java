package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderApplicationDraftService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prekinder/processes/{processId}/application-draft")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderApplicationDraftController {
    private final PrekinderApplicationDraftService drafts;
    public PrekinderApplicationDraftController(PrekinderApplicationDraftService drafts) { this.drafts = drafts; }
    @GetMapping public Map<String, Object> get(@PathVariable UUID processId) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("success", true); response.put("data", drafts.get(processId)); return response;
    }
    @PutMapping public Map<String, Object> save(@PathVariable UUID processId, @Valid @RequestBody SaveDraft request) {
        return Map.of("success", true, "data", drafts.save(processId, request.currentSection(), request.data(), request.expectedVersion()));
    }
    @DeleteMapping public Map<String, Object> delete(@PathVariable UUID processId) { drafts.delete(processId); return Map.of("success", true); }
    public record SaveDraft(@Min(0) @Max(20) int currentSection, @NotEmpty Map<String, Object> data, Long expectedVersion) {}
}
