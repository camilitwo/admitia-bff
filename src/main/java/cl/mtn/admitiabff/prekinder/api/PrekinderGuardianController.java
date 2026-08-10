package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderGuardianService;
import cl.mtn.admitiabff.prekinder.service.PrekinderGuardianFormService;
import cl.mtn.admitiabff.service.payments.PrekinderPaymentService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prekinder")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderGuardianController {
    private final PrekinderGuardianService guardian;
    private final PrekinderPaymentService payments;
    private final PrekinderGuardianFormService forms;

    public PrekinderGuardianController(PrekinderGuardianService guardian, PrekinderPaymentService payments,
                                       PrekinderGuardianFormService forms) {
        this.guardian = guardian;
        this.payments = payments;
        this.forms = forms;
    }

    @GetMapping("/me/applications")
    public Map<String, Object> applications() {
        return Map.of("success", true, "data", guardian.myApplications());
    }

    @PostMapping("/applications/{applicationId}/payments/checkout")
    public Map<String, Object> checkout(@PathVariable UUID applicationId) { return payments.checkout(applicationId); }

    @GetMapping("/applications/{applicationId}/payments/status")
    public Map<String, Object> paymentStatus(@PathVariable UUID applicationId) { return payments.status(applicationId); }

    @GetMapping("/applications/{applicationId}/complementary-form")
    public Map<String, Object> complementaryForm(@PathVariable UUID applicationId) { return forms.get(applicationId); }

    @PostMapping("/applications/{applicationId}/complementary-form")
    public Map<String, Object> saveComplementaryForm(@PathVariable UUID applicationId,
                                                      @RequestBody Map<String, Object> payload) {
        return forms.save(applicationId, payload);
    }
}
