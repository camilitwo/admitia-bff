package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderGroupClusterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
public class PrekinderGroupClusterController {
    private final PrekinderGroupClusterService clusters;

    public PrekinderGroupClusterController(PrekinderGroupClusterService clusters) { this.clusters = clusters; }

    @GetMapping("/processes/{processId}/group-clusters")
    public Map<String, Object> clusters(@PathVariable UUID processId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ok(clusters.clusters(processId, date));
    }

    @PostMapping("/group-clusters")
    public Map<String, Object> create(@Valid @RequestBody CreateClusterCommand command) {
        return ok(clusters.createCluster(command.processId(), command.evaluationDayId(), command.name(), command.groupIds()));
    }

    @PutMapping("/group-clusters/{clusterId}")
    public Map<String, Object> update(@PathVariable UUID clusterId, @Valid @RequestBody UpdateClusterCommand command) {
        return ok(clusters.updateCluster(clusterId, command.name(), command.groupIds(), command.reason(), command.expectedVersion()));
    }

    @DeleteMapping("/group-clusters/{clusterId}")
    public Map<String, Object> delete(@PathVariable UUID clusterId, @RequestParam @Min(0) long expectedVersion) {
        return ok(clusters.deleteCluster(clusterId, expectedVersion));
    }

    @PostMapping("/group-clusters/{clusterId}/groups/{groupId}")
    public Map<String, Object> addGroup(@PathVariable UUID clusterId, @PathVariable UUID groupId,
        @Valid @RequestBody VersionCommand command) {
        return ok(clusters.addGroup(clusterId, groupId, command.expectedVersion()));
    }

    @DeleteMapping("/group-clusters/{clusterId}/groups/{groupId}")
    public Map<String, Object> removeGroup(@PathVariable UUID clusterId, @PathVariable UUID groupId,
        @RequestParam @Min(0) long expectedVersion) {
        return ok(clusters.removeGroup(clusterId, groupId, expectedVersion));
    }

    @PutMapping("/group-clusters/{clusterId}/schedule")
    public Map<String, Object> reschedule(@PathVariable UUID clusterId, @Valid @RequestBody RescheduleClusterCommand command) {
        return ok(clusters.rescheduleCluster(clusterId, command.startsAt(), command.durationMinutes(),
            command.reason(), command.expectedVersion()));
    }

    @PutMapping("/group-clusters/{clusterId}/confirmation")
    public Map<String, Object> confirm(@PathVariable UUID clusterId, @Valid @RequestBody VersionCommand command) {
        return ok(clusters.confirmCluster(clusterId, command.expectedVersion()));
    }

    private static Map<String, Object> ok(Object data) { return Map.of("success", true, "data", data); }

    public record CreateClusterCommand(@NotNull UUID processId, @NotNull UUID evaluationDayId,
        @NotBlank @Size(max = 120) String name, @NotNull @Size(min = 2, max = 60) List<@NotNull UUID> groupIds) {}
    public record UpdateClusterCommand(@NotBlank @Size(max = 120) String name,
        @NotNull @Size(min = 2, max = 60) List<@NotNull UUID> groupIds,
        @Size(max = 2000) String reason, @Min(0) long expectedVersion) {}
    public record RescheduleClusterCommand(@NotNull Instant startsAt,
        @Min(10) @Max(240) Integer durationMinutes, @Size(max = 2000) String reason, @Min(0) long expectedVersion) {}
    public record VersionCommand(@Min(0) long expectedVersion) {}
}
