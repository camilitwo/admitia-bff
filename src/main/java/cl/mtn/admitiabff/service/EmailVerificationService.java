package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.user.EmailVerificationCodeEntity;
import cl.mtn.admitiabff.repository.EmailVerificationCodeRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.notification.EmailNotificationStrategy;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final int EXPIRY_MINUTES = 15;

    private final EmailVerificationCodeRepository verificationCodeRepository;
    private final UserRepository userRepository;
    private final EmailNotificationStrategy emailStrategy;

    public EmailVerificationService(
            EmailVerificationCodeRepository verificationCodeRepository,
            UserRepository userRepository,
            EmailNotificationStrategy emailStrategy) {
        this.verificationCodeRepository = verificationCodeRepository;
        this.userRepository = userRepository;
        this.emailStrategy = emailStrategy;
    }

    /**
     * POST /api/email/send-verification
     * Validates the email/rut are not already registered, generates a 6-digit OTP,
     * persists it, and dispatches it via the configured email strategy.
     */
    @Transactional
    public Map<String, Object> sendVerification(Map<String, Object> body) {
        String email = stringValue(body.get("email")).trim().toLowerCase();
        String firstName = stringValue(body.get("firstName"));
        String lastName = stringValue(body.get("lastName"));
        String rut = stringValue(body.get("rut")).trim();

        if (email.isEmpty()) {
            throw new IllegalArgumentException("EMAIL_003:Email es requerido");
        }

        // 1. Verify email not already registered
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalStateException("EMAIL_008:Este email ya está registrado en el sistema. Por favor, inicia sesión o usa otro email.");
        }

        // 2. Verify RUT not already registered (if provided)
        if (!rut.isEmpty()) {
            userRepository.findByRut(rut).ifPresent(existing ->
                    { throw new IllegalStateException("EMAIL_009:Este RUT ya está registrado en el sistema con el email: " + existing.getEmail()); });
        }

        // 3. Delete any existing pending codes for this email
        verificationCodeRepository.deleteByEmailIgnoreCase(email);

        // 4. Generate 6-digit code and persist
        String code = generateCode();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(EXPIRY_MINUTES);

        EmailVerificationCodeEntity entity = new EmailVerificationCodeEntity();
        entity.setEmail(email);
        entity.setCode(code);
        entity.setRut(rut.isEmpty() ? null : rut);
        entity.setUsed(false);
        entity.setExpiresAt(expiresAt);
        entity.setCreatedAt(LocalDateTime.now());
        verificationCodeRepository.save(entity);

        // 5. Send email
        boolean emailSent = false;
        String emailError = null;
        try {
            String subject = "Código de Verificación - Colegio MTN";
            String message = buildMessage(firstName, lastName, code);
            var notification = buildNotificationEntity(email, subject, message);
            emailStrategy.dispatch(notification);
            emailSent = true;
            log.info("Verification code sent to {}", email);
        } catch (Exception ex) {
            emailError = ex.getMessage();
            log.error("Error sending verification email to {}: {}", email, ex.getMessage());
        }

        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("success", true);
        response.put("data", Map.of(
                "message", "Código de verificación enviado",
                "email", email,
                "expiresAt", expiresAt.toString(),
                "emailSent", emailSent
        ));
        if (emailError != null) {
            response.put("emailError", emailError);
        }
        return response;
    }

    /**
     * POST /api/email/verify-code
     * Validates the OTP against the stored code. Marks it as used on success.
     */
    @Transactional
    public Map<String, Object> verifyCode(Map<String, Object> body) {
        String email = stringValue(body.get("email")).trim().toLowerCase();
        String code = stringValue(body.get("code")).trim();

        if (email.isEmpty() || code.isEmpty()) {
            throw new IllegalArgumentException("EMAIL_005:Email y código son requeridos");
        }

        EmailVerificationCodeEntity verification = verificationCodeRepository
                .findFirstByEmailIgnoreCaseAndCodeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                        email, code, LocalDateTime.now())
                .orElseThrow(() -> new IllegalArgumentException("EMAIL_006:Código inválido o expirado"));

        // Mark as used
        verification.setUsed(true);
        verificationCodeRepository.save(verification);

        log.info("Email verified successfully: {}", email);

        return Map.of(
                "success", true,
                "data", Map.of(
                        "isValid", true,
                        "verified", true,
                        "email", email
                )
        );
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String generateCode() {
        return String.format("%06d", new SecureRandom().nextInt(900000) + 100000);
    }

    private String buildMessage(String firstName, String lastName, String code) {
        String name = (firstName + " " + lastName).trim();
        return "Estimado/a " + (name.isEmpty() ? "usuario/a" : name) + ",\n\n"
                + "Tu código de verificación es: " + code + "\n\n"
                + "Este código expirará en " + EXPIRY_MINUTES + " minutos.\n\n"
                + "Si no solicitaste este código, por favor ignora este mensaje.\n\n"
                + "Saludos cordiales,\nColegio MTN";
    }

    private cl.mtn.admitiabff.domain.notification.NotificationEntity buildNotificationEntity(
            String to, String subject, String message) {
        var n = new cl.mtn.admitiabff.domain.notification.NotificationEntity();
        n.setRecipient(to);
        n.setSubject(subject);
        n.setMessage(message);
        n.setChannel(cl.mtn.admitiabff.domain.common.NotificationChannel.EMAIL);
        n.setType("EMAIL_VERIFICATION");
        n.setStatus(cl.mtn.admitiabff.domain.common.NotificationStatus.PENDING);
        n.setCreatedAt(LocalDateTime.now());
        return n;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
