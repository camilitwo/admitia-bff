package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderPublicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prekinder")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderPublicationController {
    private final PrekinderPublicationService publications;

    public PrekinderPublicationController(PrekinderPublicationService publications) {
        this.publications = publications;
    }

    @PostMapping("/processes/{processId}/publication-previews")
    public Map<String, Object> preview(@PathVariable UUID processId) {
        return ok(publications.preview(processId));
    }

    @PostMapping("/processes/{processId}/publication-batches")
    public Map<String, Object> create(@PathVariable UUID processId, @Valid @RequestBody CreateBatch request) {
        return ok(publications.create(processId, new PrekinderPublicationService.CreateBatch(
            request.previewId(), request.idempotencyKey(), request.mode(), request.scheduledAt())));
    }

    @GetMapping("/processes/{processId}/publication-batches")
    public Map<String, Object> batches(@PathVariable UUID processId) { return ok(publications.batches(processId)); }

    @GetMapping("/publication-batches/{batchId}")
    public Map<String, Object> detail(@PathVariable UUID batchId) { return ok(publications.detail(batchId)); }

    @PostMapping("/publication-batches/{batchId}/cancellation")
    public Map<String, Object> cancel(@PathVariable UUID batchId, @RequestParam @Min(0) long expectedVersion) {
        return ok(publications.cancel(batchId, expectedVersion));
    }

    @PostMapping("/publication-batches/{batchId}/retry")
    public Map<String, Object> retry(@PathVariable UUID batchId) { return ok(publications.retry(batchId)); }

    private static Map<String, Object> ok(Object data) { return Map.of("success", true, "data", data); }

    public record CreateBatch(@NotNull UUID previewId, @NotNull UUID idempotencyKey,
        @NotBlank @Pattern(regexp = "IMMEDIATE|SCHEDULED") String mode, Instant scheduledAt) {}
}
