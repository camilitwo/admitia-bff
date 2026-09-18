package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderReferralService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prekinder")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderReferralController {
    private final PrekinderReferralService referrals;
    public PrekinderReferralController(PrekinderReferralService referrals) { this.referrals = referrals; }

    @PostMapping("/applications/{applicationId}/referrals")
    public Map<String, Object> suggest(@PathVariable UUID applicationId, @Valid @RequestBody Suggest request) {
        return ok(referrals.suggest(applicationId, request.sourceReportId(), request.targetType(), request.rationale()));
    }

    @PutMapping("/referrals/{referralId}/review")
    public Map<String, Object> review(@PathVariable UUID referralId, @Valid @RequestBody Review request) {
        return ok(referrals.review(referralId, request.decision(), request.rationale(), request.expectedVersion()));
    }

    @GetMapping("/applications/{applicationId}/referrals")
    public Map<String, Object> list(@PathVariable UUID applicationId) { return ok(referrals.list(applicationId)); }

    private static Map<String, Object> ok(Object data) { return Map.of("success", true, "data", data); }
    public record Suggest(@NotNull UUID sourceReportId,
        @NotBlank @Pattern(regexp = "LEARNING_SUPPORT|DAP") String targetType,
        @NotBlank @Size(max = 4000) String rationale) {}
    public record Review(@NotBlank @Pattern(regexp = "APPROVED|REJECTED|REQUIRES_INFORMATION") String decision,
        @NotBlank @Size(max = 4000) String rationale, @Min(0) long expectedVersion) {}
}
