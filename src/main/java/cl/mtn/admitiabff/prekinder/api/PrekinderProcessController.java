package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderProcessLifecycleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prekinder/processes/{processId}")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderProcessController {
    private final PrekinderProcessLifecycleService processes;

    public PrekinderProcessController(PrekinderProcessLifecycleService processes) {
        this.processes = processes;
    }

    @PatchMapping
    public Map<String, Object> update(@PathVariable UUID processId, @Valid @RequestBody UpdateProcess request) {
        return ok(processes.updateProcess(processId, new PrekinderProcessLifecycleService.ProcessCommand(
            request.academicYear(), request.name(), request.expectedVersion())));
    }

    @GetMapping("/configuration")
    public Map<String, Object> configuration(@PathVariable UUID processId) {
        return ok(processes.configuration(processId));
    }

    @PutMapping("/configuration")
    public Map<String, Object> configuration(@PathVariable UUID processId,
        @Valid @RequestBody SaveConfiguration request) {
        return ok(processes.saveConfiguration(processId,
            new PrekinderProcessLifecycleService.ConfigurationCommand(
                request.paymentEnabled(), request.paymentAmount(), request.paymentCurrency(),
                request.paymentGlosa(), request.paymentDueDays(), request.inclusionEnabled(),
                request.inclusionDocumentsRequired(), request.minimumAgeMonths(), request.maximumAgeMonths(),
                request.applicantWeight(), request.familyWeight(), request.expectedVersion())));
    }

    @GetMapping("/readiness")
    public Map<String, Object> readiness(@PathVariable UUID processId) {
        return ok(processes.readiness(processId));
    }

    @PostMapping("/close")
    public Map<String, Object> close(@PathVariable UUID processId, @RequestParam @Min(0) long expectedVersion) {
        return ok(processes.close(processId, expectedVersion));
    }

    @PostMapping("/archive")
    public Map<String, Object> archive(@PathVariable UUID processId, @RequestParam @Min(0) long expectedVersion) {
        return ok(processes.archive(processId, expectedVersion));
    }

    private static Map<String, Object> ok(Object data) {
        return Map.of("success", true, "data", data);
    }

    public record UpdateProcess(@Min(2027) @Max(2100) int academicYear,
        @NotBlank @Size(max = 160) String name, @Min(0) long expectedVersion) {}

    public record SaveConfiguration(boolean paymentEnabled,
        @DecimalMin(value = "0.01") BigDecimal paymentAmount,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String paymentCurrency,
        @NotBlank @Size(max = 180) String paymentGlosa,
        @Min(1) @Max(30) int paymentDueDays,
        boolean inclusionEnabled, boolean inclusionDocumentsRequired,
        @Min(36) @Max(84) int minimumAgeMonths,
        @Min(36) @Max(96) int maximumAgeMonths,
        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal applicantWeight,
        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal familyWeight,
        @Min(0) long expectedVersion) {}
}
