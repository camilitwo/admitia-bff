package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderEvaluatorService;
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
public class PrekinderEvaluatorController {
    private final PrekinderEvaluatorService evaluators;
    public PrekinderEvaluatorController(PrekinderEvaluatorService evaluators) { this.evaluators = evaluators; }

    @GetMapping("/instruments")
    public Map<String, Object> instruments() { return ok(evaluators.instruments()); }

    @GetMapping("/me/evaluator-agenda")
    public Map<String, Object> agenda(@RequestParam(required = false) UUID processId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam String instrument) {
        return ok(evaluators.agenda(processId, date, instrument));
    }

    @GetMapping("/me/evaluator-workspace")
    public Map<String, Object> workspace(@RequestParam(required = false) UUID processId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ok(evaluators.workspace(processId, date));
    }

    @GetMapping("/me/rubrics")
    public Map<String, Object> rubrics(@RequestParam(required = false) UUID processId) {
        return ok(evaluators.rubrics(processId));
    }

    @PutMapping("/groups/{groupId}/instruments/{instrumentCode}/assignment")
    public Map<String, Object> assign(@PathVariable UUID groupId, @PathVariable String instrumentCode,
                                      @Valid @RequestBody AssignmentCommand command) {
        return ok(evaluators.assign(groupId, instrumentCode, command.evaluatorId(), command.templateVersionId(),
            command.reason(), command.expectedVersion(), command.operationId()));
    }

    @PutMapping("/processes/{processId}/professionals/{professionalId}/instruments/{instrumentCode}")
    public Map<String, Object> authorize(@PathVariable UUID processId, @PathVariable UUID professionalId,
                                         @PathVariable String instrumentCode,
                                         @Valid @RequestBody OperationCommand command) {
        evaluators.authorize(processId, professionalId, instrumentCode, command.operationId());
        return ok(Map.of("authorized", true));
    }

    @PostMapping("/evaluator-assignments/{assignmentId}/confirm")
    public Map<String, Object> confirm(@PathVariable UUID assignmentId, @Valid @RequestBody TransitionCommand command) {
        return ok(evaluators.transition(assignmentId, "confirm", command.expectedVersion(), command.operationId()));
    }

    @PostMapping("/evaluator-assignments/{assignmentId}/start")
    public Map<String, Object> start(@PathVariable UUID assignmentId, @Valid @RequestBody TransitionCommand command) {
        return ok(evaluators.transition(assignmentId, "start", command.expectedVersion(), command.operationId()));
    }

    @PostMapping("/evaluator-assignments/{assignmentId}/submit")
    public Map<String, Object> submit(@PathVariable UUID assignmentId, @Valid @RequestBody TransitionCommand command) {
        return ok(evaluators.transition(assignmentId, "submit", command.expectedVersion(), command.operationId()));
    }

    @GetMapping("/evaluator-assignments/{assignmentId}/capture")
    public Map<String, Object> capture(@PathVariable UUID assignmentId) { return ok(evaluators.capture(assignmentId)); }

    private static Map<String, Object> ok(Object data) { return Map.of("success", true, "data", data); }
    public record AssignmentCommand(@NotNull UUID evaluatorId, @NotNull UUID templateVersionId,
                                    @NotBlank @Size(max = 2000) String reason, @Min(0) long expectedVersion,
                                    @NotNull UUID operationId) {}
    public record TransitionCommand(@Min(0) long expectedVersion, @NotNull UUID operationId) {}
    public record OperationCommand(@NotNull UUID operationId) {}
}
