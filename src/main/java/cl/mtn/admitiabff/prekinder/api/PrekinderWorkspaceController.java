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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @PostMapping("/applications")
    public Map<String, Object> application(@Valid @RequestBody CreateApplication request) {
        return Map.of("success", true, "data", workspace.createApplication(request.processId(),
            new PrekinderWorkspaceService.Identity(request.firstName().trim(), request.paternalLastName().trim(),
                request.maternalLastName() == null ? "" : request.maternalLastName().trim())));
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
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String paternalLastName,
        @Size(max = 100) String maternalLastName) {}
    public record CreateEvaluation(@NotBlank @Pattern(regexp = "[A-Z0-9_]{2,64}") String typeCode) {}
    public record UpdateField(@NotNull UUID operationId, @Min(0) long expectedVersion,
                              @NotNull @Size(max = 12000) String content) {}
}
