package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderRubricService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
public class PrekinderRubricController {
    private final PrekinderRubricService rubrics;

    public PrekinderRubricController(PrekinderRubricService rubrics) { this.rubrics = rubrics; }

    @GetMapping("/rubrics")
    public Map<String, Object> catalog() { return ok(rubrics.catalog()); }

    @PostMapping("/rubrics")
    public Map<String, Object> create(@Valid @RequestBody CreateRubric request) {
        return ok(rubrics.create(new PrekinderRubricService.CreateRubric(request.name(), request.instrumentCode())));
    }

    @GetMapping("/rubrics/{rubricId}")
    public Map<String, Object> detail(@PathVariable UUID rubricId) { return ok(rubrics.detail(rubricId)); }

    @PostMapping("/rubrics/{rubricId}/versions")
    public Map<String, Object> duplicate(@PathVariable UUID rubricId) { return ok(rubrics.duplicate(rubricId)); }

    @GetMapping("/rubric-versions/{versionId}")
    public Map<String, Object> version(@PathVariable UUID versionId) { return ok(rubrics.version(versionId)); }

    @PutMapping("/rubric-versions/{versionId}")
    public Map<String, Object> save(@PathVariable UUID versionId, @Valid @RequestBody SaveDraft request) {
        List<PrekinderRubricService.CriterionCommand> criteria = request.criteria().stream().map(criterion ->
            new PrekinderRubricService.CriterionCommand(criterion.code(), criterion.name(), criterion.descriptor(),
                criterion.required(), criterion.options().stream().map(option ->
                    new PrekinderRubricService.OptionCommand(option.value(), option.label(), option.descriptor(),
                        option.professionallyValidated())).toList())).toList();
        return ok(rubrics.saveDraft(versionId, new PrekinderRubricService.DraftCommand(
            request.name(), request.instrumentCode(), request.expectedRubricVersion(), criteria)));
    }

    @PostMapping("/rubric-versions/{versionId}/publication")
    public Map<String, Object> publish(@PathVariable UUID versionId) { return ok(rubrics.publish(versionId)); }

    @DeleteMapping("/rubric-versions/{versionId}")
    public Map<String, Object> delete(@PathVariable UUID versionId) {
        rubrics.deleteDraft(versionId);
        return ok(Map.of("versionId", versionId, "deleted", true));
    }

    @PostMapping("/rubrics/{rubricId}/archive")
    public Map<String, Object> archive(@PathVariable UUID rubricId,
        @RequestParam @Min(0) long expectedVersion) {
        return ok(rubrics.archive(rubricId, expectedVersion));
    }

    @GetMapping("/processes/{processId}/rubric-assignments")
    public Map<String, Object> assignments(@PathVariable UUID processId) {
        return ok(rubrics.assignments(processId));
    }

    @PutMapping("/processes/{processId}/rubric-assignments/{instrumentCode}")
    public Map<String, Object> assign(@PathVariable UUID processId, @PathVariable String instrumentCode,
        @Valid @RequestBody AssignRubric request) {
        return ok(rubrics.assign(processId, instrumentCode, request.versionId(), request.expectedVersion()));
    }

    private static Map<String, Object> ok(Object data) { return Map.of("success", true, "data", data); }

    public record CreateRubric(@NotBlank @Size(max = 160) String name,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{2,64}") String instrumentCode) {}
    public record SaveDraft(@NotBlank @Size(max = 160) String name,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{2,64}") String instrumentCode,
        @Min(0) long expectedRubricVersion,
        @NotEmpty List<@Valid Criterion> criteria) {}
    public record Criterion(@NotBlank @Pattern(regexp = "[A-Za-z0-9_]{1,64}") String code,
        @NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 2000) String descriptor,
        boolean required, @Size(min = 2, max = 20) List<@Valid Option> options) {}
    public record Option(@NotNull @DecimalMin("0") BigDecimal value,
        @NotBlank @Size(max = 160) String label, @NotBlank @Size(max = 2000) String descriptor,
        boolean professionallyValidated) {}
    public record AssignRubric(@NotNull UUID versionId, Long expectedVersion) {}
}
