package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.EmailVerificationService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/email")
public class EmailController {

    private final EmailVerificationService emailVerificationService;

    public EmailController(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }

    /**
     * POST /api/email/send-verification
     * Body: { email, firstName?, lastName?, rut? }
     * Validates that email/RUT are not already registered, generates a 6-digit OTP,
     * stores it in email_verification_codes, and dispatches it via email.
     */
    @PostMapping("/send-verification")
    public ResponseEntity<Map<String, Object>> sendVerification(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(emailVerificationService.sendVerification(body));
    }

    /**
     * POST /api/email/verify-code
     * Body: { email, code }
     * Validates the OTP against the stored code and marks it as used.
     */
    @PostMapping("/verify-code")
    public ResponseEntity<Map<String, Object>> verifyCode(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(emailVerificationService.verifyCode(body));
    }
}
