package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.AuthService;
import cl.mtn.admitiabff.service.payments.PaymentService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentsController {
    private final PaymentService paymentService;
    private final AuthService authService;

    public PaymentsController(PaymentService paymentService, AuthService authService) {
        this.paymentService = paymentService;
        this.authService = authService;
    }

    @PostMapping("/applications/{applicationId}/checkout")
    public Map<String, Object> checkout(@PathVariable Long applicationId) {
        return paymentService.checkout(applicationId, authService.requireAuth().id());
    }

    @GetMapping("/applications/{applicationId}/status")
    public Map<String, Object> status(@PathVariable Long applicationId) {
        return paymentService.status(applicationId, authService.requireAuth().id());
    }

    @PostMapping("/webhooks/toku")
    public Map<String, Object> tokuWebhook(@RequestHeader(name = "Toku-Signature", required = false) String signature,
                                           @RequestBody String rawBody) {
        return paymentService.tokuWebhook(signature, rawBody);
    }
}
