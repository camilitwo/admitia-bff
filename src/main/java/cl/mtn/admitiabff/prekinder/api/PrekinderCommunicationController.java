package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderCommunicationTemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prekinder")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderCommunicationController {
    private final PrekinderCommunicationTemplateService communications;
    public PrekinderCommunicationController(PrekinderCommunicationTemplateService communications) {
        this.communications = communications;
    }

    @GetMapping("/processes/{processId}/communication-templates")
    public Map<String, Object> templates(@PathVariable UUID processId) { return ok(communications.templates(processId)); }

    @PostMapping("/communication-templates/{templateId}/versions")
    public Map<String, Object> duplicate(@PathVariable UUID templateId) { return ok(communications.duplicate(templateId)); }

    @PutMapping("/communication-template-versions/{versionId}")
    public Map<String, Object> save(@PathVariable UUID versionId, @Valid @RequestBody SaveTemplate request) {
        return ok(communications.save(versionId, request.subject(), request.bodyHtml()));
    }

    @PostMapping("/communication-template-versions/{versionId}/publication")
    public Map<String, Object> publish(@PathVariable UUID versionId) { return ok(communications.publish(versionId)); }

    @DeleteMapping("/communication-template-versions/{versionId}")
    public Map<String, Object> deleteDraft(@PathVariable UUID versionId) {
        communications.deleteDraft(versionId);
        return Map.of("success", true);
    }

    @GetMapping("/communication-template-versions/{versionId}/preview")
    public Map<String, Object> preview(@PathVariable UUID versionId) { return ok(communications.preview(versionId)); }

    @PostMapping("/communication-templates/{templateId}/archive")
    public Map<String, Object> archive(@PathVariable UUID templateId,
        @RequestParam @Min(0) long expectedVersion) {
        return ok(communications.archive(templateId, expectedVersion));
    }

    private static Map<String, Object> ok(Object data) { return Map.of("success", true, "data", data); }
    public record SaveTemplate(@NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 20000) String bodyHtml) {}
}
