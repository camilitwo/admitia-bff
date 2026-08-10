package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderControlTowerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prekinder")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderControlTowerController {
    private final PrekinderControlTowerService controlTower;
    public PrekinderControlTowerController(PrekinderControlTowerService controlTower) { this.controlTower = controlTower; }

    @GetMapping("/processes/{processId}/control-tower")
    public Map<String, Object> controlTower(@PathVariable UUID processId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ok(controlTower.controlTower(processId, date));
    }

    @PatchMapping("/groups/{groupId}/members/{applicationId}/attendance")
    public Map<String, Object> attendance(@PathVariable UUID groupId, @PathVariable UUID applicationId,
                                          @Valid @RequestBody AttendanceCommand command) {
        return ok(controlTower.updateAttendance(groupId, applicationId, command.status(), command.reasonCode(),
            command.expectedVersion(), command.operationId()));
    }

    private static Map<String, Object> ok(Object data) { return Map.of("success", true, "data", data); }
    public record AttendanceCommand(@NotBlank String status, @Size(max = 64) String reasonCode,
                                    @Min(0) long expectedVersion, @NotNull UUID operationId) {}
}
