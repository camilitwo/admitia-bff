package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderWorkspaceService;
import cl.mtn.admitiabff.prekinder.service.PrekinderFieldService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
public class PrekinderWorkspaceController {
    private final PrekinderWorkspaceService workspace;
    private final PrekinderFieldService fields;
    public PrekinderWorkspaceController(PrekinderWorkspaceService workspace, PrekinderFieldService fields) {
        this.workspace = workspace; this.fields = fields;
    }

    @PostMapping("/processes")
    public Map<String, Object> process(@Valid @RequestBody CreateProcess request) {
        return Map.of("success", true, "data", workspace.createProcess(request.academicYear(), request.name().trim()));
    }

    @GetMapping("/processes")
    public Map<String, Object> processes() {
        return Map.of("success", true, "data", workspace.listProcesses());
    }

    @GetMapping("/application-options")
    public Map<String, Object> applicationOptions() {
        return Map.of("success", true, "data", workspace.applicationOptions());
    }

    @PutMapping("/processes/{processId}/publication")
    public Map<String, Object> publish(@PathVariable UUID processId, @Valid @RequestBody PublishProcess request) {
        return Map.of("success", true, "data", workspace.publishProcess(processId, request.startsAt(), request.endsAt()));
    }

    @PostMapping("/legacy/applications")
    public Map<String, Object> application(@Valid @RequestBody CreateApplication request) {
        return Map.of("success", true, "data", workspace.createApplication(request.processId(),
            new PrekinderWorkspaceService.Identity(request.rut(), request.firstName().trim(), request.paternalLastName().trim(),
                request.maternalLastName() == null ? "" : request.maternalLastName().trim())));
    }

    @GetMapping("/legacy/applications")
    public Map<String, Object> applications(@RequestParam UUID processId) {
        return Map.of("success", true, "data", workspace.listApplications(processId));
    }

    @PostMapping("/applications/{applicationId}/evaluations")
    public Map<String, Object> evaluation(@PathVariable UUID applicationId, @Valid @RequestBody CreateEvaluation request) {
        return Map.of("success", true, "data", workspace.createEvaluation(applicationId, request.typeCode()));
    }

    @GetMapping("/evaluations")
    public Map<String, Object> evaluations() {
        return Map.of("success", true, "data", workspace.listEvaluations());
    }

    @org.springframework.web.bind.annotation.PutMapping("/evaluations/{evaluationId}/fields/{fieldCode}")
    public Map<String, Object> field(@PathVariable UUID evaluationId, @PathVariable String fieldCode,
                                     @Valid @RequestBody UpdateField request) {
        if (!fieldCode.matches("[A-Z0-9_]{2,96}")) throw new IllegalArgumentException("Campo inválido");
        return Map.of("success", true, "data", fields.update(evaluationId, fieldCode, request.expectedVersion(),
            request.operationId(), request.content()));
    }

    public record CreateProcess(@Min(2027) @Max(2100) int academicYear,
                                @NotBlank @Size(max = 160) String name) {}
    public record CreateApplication(@NotNull UUID processId,
        @NotBlank @Size(max = 16) String rut,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String paternalLastName,
        @Size(max = 100) String maternalLastName) {}
    public record PublishProcess(@NotNull Instant startsAt, @NotNull Instant endsAt) {}
    public record CreateEvaluation(@NotBlank @Pattern(regexp = "[A-Z0-9_]{2,64}") String typeCode) {}
    public record UpdateField(@NotNull UUID operationId, @Min(0) long expectedVersion,
                              @NotNull @Size(max = 12000) String content) {}
}
