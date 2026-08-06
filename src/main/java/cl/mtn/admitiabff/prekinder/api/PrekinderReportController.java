package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prekinder/reports")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderReportController {
    private final PrekinderReportService reports;
    public PrekinderReportController(PrekinderReportService reports) { this.reports = reports; }

    @GetMapping("/{reportId}")
    public Map<String, Object> report(@PathVariable UUID reportId) { return ok(reports.report(reportId)); }

    @PutMapping("/{reportId}/criteria/{criterionId}")
    public Map<String, Object> response(@PathVariable UUID reportId, @PathVariable UUID criterionId,
                                       @Valid @RequestBody ResponseCommand command) {
        return ok(reports.saveResponse(reportId, criterionId, command.optionId(), command.notObserved(),
            command.expectedVersion(), command.operationId()));
    }

    @PutMapping("/{reportId}/note")
    public Map<String, Object> note(@PathVariable UUID reportId, @Valid @RequestBody NoteCommand command) {
        return ok(reports.saveNote(reportId, command.content(), command.expectedVersion(), command.operationId()));
    }

    @PutMapping("/{reportId}/completion")
    public Map<String, Object> complete(@PathVariable UUID reportId, @Valid @RequestBody VersionCommand command) {
        return ok(reports.complete(reportId, command.expectedVersion()));
    }

    @PutMapping("/{reportId}/extension")
    public Map<String, Object> extension(@PathVariable UUID reportId, @Valid @RequestBody ExtensionCommand command) {
        return ok(reports.extend(reportId, command.validUntil(), command.reason()));
    }

    private static Map<String, Object> ok(Object data) { return Map.of("success", true, "data", data); }
    public record ResponseCommand(UUID optionId, boolean notObserved, @Min(0) long expectedVersion,
                                  @NotNull UUID operationId) {}
    public record NoteCommand(@Size(max = 12000) String content, @Min(0) long expectedVersion,
                              @NotNull UUID operationId) {}
    public record VersionCommand(@Min(0) long expectedVersion) {}
    public record ExtensionCommand(@NotNull @Future Instant validUntil, @NotNull @Size(min = 3, max = 2000) String reason) {}
}
