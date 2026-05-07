package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import cl.mtn.admitiabff.domain.user.EmailVerificationCodeEntity;
import cl.mtn.admitiabff.repository.EmailVerificationCodeRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maneja el código OTP de verificación de email para el registro.
 * <p>El envío real del correo se delega 100% a {@link EmailComposerService}
 * usando el template {@link EmailTemplate#EMAIL_VERIFICATION}; este servicio
 * sólo valida y persiste el código.
 */
@Service
@Transactional(readOnly = true)
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final int EXPIRY_MINUTES = 15;

    private final EmailVerificationCodeRepository verificationCodeRepository;
    private final UserRepository userRepository;
    private final EmailComposerService emailComposerService;

    public EmailVerificationService(
            EmailVerificationCodeRepository verificationCodeRepository,
            UserRepository userRepository,
            EmailComposerService emailComposerService) {
        this.verificationCodeRepository = verificationCodeRepository;
        this.userRepository = userRepository;
        this.emailComposerService = emailComposerService;
    }

    /**
     * POST /api/email/send-verification
     * Valida que el email/RUT no estén registrados, genera un OTP de 6 dígitos,
     * lo persiste y lo despacha vía el composer (Resend) con el template
     * {@code EMAIL_VERIFICATION}.
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

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalStateException("EMAIL_008:Este email ya está registrado en el sistema. Por favor, inicia sesión o usa otro email.");
        }

        if (!rut.isEmpty()) {
            userRepository.findByRut(rut).ifPresent(existing -> {
                throw new IllegalStateException("EMAIL_009:Este RUT ya está registrado en el sistema con el email: " + existing.getEmail());
            });
        }

        verificationCodeRepository.deleteByEmailIgnoreCase(email);

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

        boolean emailSent = false;
        String emailError = null;
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("code", code);
            data.put("recipientName", (firstName + " " + lastName).trim());
            data.put("expiresInMinutes", EXPIRY_MINUTES);

            emailComposerService.send(EmailComposerService.EmailRequest.builder()
                    .template(EmailTemplate.EMAIL_VERIFICATION)
                    .to(email)
                    .recipientType("USER")
                    .data(data)
                    .build());
            emailSent = true;
            log.info("Verification code sent to {}", email);
        } catch (Exception ex) {
            emailError = ex.getMessage();
            log.error("Error sending verification email to {}: {}", email, ex.getMessage(), ex);
        }

        Map<String, Object> response = new LinkedHashMap<>();
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

    /** POST /api/email/verify-code — valida el OTP y lo marca como usado. */
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

    private String generateCode() {
        return String.format("%06d", new SecureRandom().nextInt(900000) + 100000);
    }


    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
