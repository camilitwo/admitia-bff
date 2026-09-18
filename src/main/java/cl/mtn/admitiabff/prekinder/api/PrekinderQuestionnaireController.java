package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderQuestionnaireService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.Map;
import java.util.UUID;
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
public class PrekinderQuestionnaireController {
    private final PrekinderQuestionnaireService questionnaires;

    public PrekinderQuestionnaireController(PrekinderQuestionnaireService questionnaires) {
        this.questionnaires = questionnaires;
    }

    @GetMapping("/processes/{processId}/questionnaire")
    public Map<String, Object> get(@PathVariable UUID processId) {
        return ok(questionnaires.get(processId));
    }

    @PostMapping("/processes/{processId}/questionnaire/versions")
    public Map<String, Object> duplicate(@PathVariable UUID processId) {
        return ok(questionnaires.duplicate(processId));
    }

    @PutMapping("/processes/{processId}/questionnaire/versions/{versionId}")
    public Map<String, Object> save(@PathVariable UUID processId, @PathVariable UUID versionId,
                                    @Valid @RequestBody SaveQuestionnaire request) {
        return ok(questionnaires.save(processId, versionId, request.schema(), request.expectedVersion()));
    }

    @PostMapping("/processes/{processId}/questionnaire/versions/{versionId}/publication")
    public Map<String, Object> publish(@PathVariable UUID processId, @PathVariable UUID versionId,
                                       @RequestParam @Min(0) long expectedVersion) {
        return ok(questionnaires.publish(processId, versionId, expectedVersion));
    }

    private static Map<String, Object> ok(Object data) { return Map.of("success", true, "data", data); }

    public record SaveQuestionnaire(@NotEmpty Map<String, Object> schema, @Min(0) long expectedVersion) {}
}
