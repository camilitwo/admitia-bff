package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.config.AuthContext;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ActiveSessionRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.util.TemplateUtils;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemporaryPasswordService {
    private static final Logger log = LoggerFactory.getLogger(TemporaryPasswordService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] LOWER = "abcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final char[] DIGITS = "23456789".toCharArray();
    private static final char[] SYMBOLS = "!@#$%*-_".toCharArray();
    private static final char[] ALL = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%*-_".toCharArray();
    private static final DateTimeFormatter EMAIL_EXPIRY = DateTimeFormatter.ofPattern("dd-MM-yyyy 'a las' HH:mm");

    private final UserRepository userRepository;
    private final ActiveSessionRepository activeSessionRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final FirebaseCredentialService firebaseCredentialService;
    private final EmailComposerService emailComposerService;
    private final long validityHours;
    private final long cooldownSeconds;
    private final String frontendBaseUrl;

    public TemporaryPasswordService(
            UserRepository userRepository,
            ActiveSessionRepository activeSessionRepository,
            TokenService tokenService,
            PasswordEncoder passwordEncoder,
            FirebaseCredentialService firebaseCredentialService,
            EmailComposerService emailComposerService,
            @Value("${app.temporary-password.validity-hours:24}") long validityHours,
            @Value("${app.temporary-password.reset-cooldown-seconds:60}") long cooldownSeconds,
            @Value("${app.frontend.base-url}") String frontendBaseUrl) {
        this.userRepository = userRepository;
        this.activeSessionRepository = activeSessionRepository;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.firebaseCredentialService = firebaseCredentialService;
        this.emailComposerService = emailComposerService;
        this.validityHours = validityHours;
        this.cooldownSeconds = cooldownSeconds;
        this.frontendBaseUrl = frontendBaseUrl.replaceAll("/+$", "");
    }

    @Transactional
    public Map<String, Object> reset(Long targetId) {
        UserEntity admin = requireCurrentUser();
        if (admin.getRole() != Role.ADMIN) {
            throw new SecurityWorkflowException("FORBIDDEN", "Sólo un administrador puede restablecer contraseñas", HttpStatus.FORBIDDEN);
        }
        UserEntity target = userRepository.findById(targetId)
            .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        validateTarget(admin, target);

        LocalDateTime now = LocalDateTime.now();
        if (target.getPasswordResetAt() != null) {
            long elapsed = Duration.between(target.getPasswordResetAt(), now).toSeconds();
            if (elapsed >= 0 && elapsed < cooldownSeconds) {
                throw new SecurityWorkflowException("PASSWORD_RESET_RATE_LIMITED",
                    "Espere un minuto antes de generar otra contraseña para este usuario", HttpStatus.TOO_MANY_REQUESTS);
            }
        }

        String temporaryPassword = generatePassword();
        LocalDateTime expiresAt = now.plusHours(validityHours);
        String loginUrl = frontendBaseUrl + (target.getRole() == Role.ADMIN ? "/admin/login" : "/profesor/login");
        Map<String, Object> emailData = new LinkedHashMap<>();
        emailData.put("name", fullName(target));
        emailData.put("temporaryPassword", temporaryPassword);
        emailData.put("expiresAt", expiresAt.format(EMAIL_EXPIRY));
        emailData.put("loginUrl", loginUrl);

        log.info("event=password_reset_requested adminId={} userId={} emailStatus=pending", admin.getId(), target.getId());
        try {
            emailComposerService.send(EmailRequestDTO.builder()
                .template(TemplateUtils.generateTemplate(EmailTemplate.TEMPORARY_PASSWORD.name(), emailData))
                .templateName(EmailTemplate.TEMPORARY_PASSWORD.name())
                .to(target.getEmail())
                .subject(EmailTemplate.TEMPORARY_PASSWORD.getDefaultSubject())
                .recipientType("USER")
                .recipientId(target.getId())
                .data(emailData)
                .sensitive(true)
                .build());
        } catch (RuntimeException ex) {
            log.warn("event=password_reset_failed adminId={} userId={} stage=email emailStatus=failed",
                admin.getId(), target.getId());
            throw ex;
        }
        log.info("event=password_reset_email_accepted adminId={} userId={} emailStatus=accepted", admin.getId(), target.getId());

        try {
            String encodedTemporaryPassword = passwordEncoder.encode(temporaryPassword);
            if (isFirebaseLinked(target)) {
                firebaseCredentialService.revokeRefreshTokens(target.getFirebaseUid());
                firebaseCredentialService.updatePassword(target.getFirebaseUid(), temporaryPassword);
            } else {
                target.setPasswordHash(encodedTemporaryPassword);
            }
            target.setTemporaryPasswordHash(encodedTemporaryPassword);
            target.setTemporaryPasswordExpiresAt(expiresAt);
            target.setMustChangePassword(true);
            target.setPasswordResetAt(now);
            target.setPasswordResetBy(admin.getId());
            userRepository.save(target);
            tokenService.revokeAllForUser(target, "ADMIN_PASSWORD_RESET");
            activeSessionRepository.deleteByUser(target);
        } catch (RuntimeException ex) {
            log.error("event=password_reset_failed adminId={} userId={} stage=credential emailStatus=accepted",
                admin.getId(), target.getId());
            throw ex;
        }

        log.info("event=password_reset_completed adminId={} userId={} emailStatus=accepted", admin.getId(), target.getId());
        return Map.of(
            "success", true,
            "message", "Contraseña temporal enviada",
            "data", Map.of("email", target.getEmail(), "expiresAt", expiresAt.toString(), "notificationSent", true));
    }

    @Transactional
    public Map<String, Object> change(Map<String, Object> payload) {
        UserEntity user = requireCurrentUser();
        if (!user.isMustChangePassword()) {
            throw new SecurityWorkflowException("TEMPORARY_PASSWORD_NOT_REQUIRED",
                "La cuenta no requiere un cambio de contraseña temporal", HttpStatus.CONFLICT);
        }
        ensureNotExpired(user);
        String newPassword = payload == null ? "" : String.valueOf(payload.getOrDefault("newPassword", ""));
        validateNewPassword(newPassword);
        if (user.getTemporaryPasswordHash() != null && passwordEncoder.matches(newPassword, user.getTemporaryPasswordHash())) {
            throw new IllegalArgumentException("La nueva contraseña debe ser distinta de la contraseña temporal");
        }

        if (isFirebaseLinked(user)) {
            firebaseCredentialService.updatePassword(user.getFirebaseUid(), newPassword);
        } else {
            user.setPasswordHash(passwordEncoder.encode(newPassword));
        }
        user.setMustChangePassword(false);
        user.setTemporaryPasswordHash(null);
        user.setTemporaryPasswordExpiresAt(null);
        userRepository.save(user);
        log.info("event=temporary_password_changed userId={}", user.getId());
        return Map.of("success", true, "message", "Contraseña actualizada correctamente");
    }

    public static void ensureNotExpired(UserEntity user) {
        if (user.isMustChangePassword() && (user.getTemporaryPasswordExpiresAt() == null
                || !user.getTemporaryPasswordExpiresAt().isAfter(LocalDateTime.now()))) {
            throw new SecurityWorkflowException("TEMPORARY_PASSWORD_EXPIRED",
                "La contraseña temporal venció. Solicite una nueva al administrador", HttpStatus.FORBIDDEN);
        }
    }

    private UserEntity requireCurrentUser() {
        var auth = AuthContext.get();
        if (auth == null || auth.id() == null) {
            throw new SecurityWorkflowException("UNAUTHORIZED", "No autenticado", HttpStatus.UNAUTHORIZED);
        }
        return userRepository.findById(auth.id()).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
    }

    private void validateTarget(UserEntity admin, UserEntity target) {
        if (admin.getId().equals(target.getId())) {
            throw new IllegalArgumentException("No puede restablecer su propia contraseña desde esta acción");
        }
        if (target.getRole() == Role.APODERADO) {
            throw new IllegalArgumentException("El restablecimiento administrativo sólo está disponible para personal");
        }
        if (!target.isActive()) throw new IllegalArgumentException("El usuario debe estar activo");
        if (target.getEmail() == null || target.getEmail().isBlank()) throw new IllegalArgumentException("El usuario no tiene un correo válido");
    }

    private void validateNewPassword(String password) {
        if (password.length() < 8 || password.length() > 128
                || !password.matches(".*[A-Z].*")
                || !password.matches(".*[a-z].*")
                || !password.matches(".*[0-9].*")) {
            throw new IllegalArgumentException("La contraseña debe tener entre 8 y 128 caracteres, mayúscula, minúscula y número");
        }
    }

    private String generatePassword() {
        char[] result = new char[16];
        result[0] = random(UPPER);
        result[1] = random(LOWER);
        result[2] = random(DIGITS);
        result[3] = random(SYMBOLS);
        for (int i = 4; i < result.length; i++) result[i] = random(ALL);
        for (int i = result.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = result[i]; result[i] = result[j]; result[j] = tmp;
        }
        return new String(result);
    }

    private char random(char[] chars) { return chars[RANDOM.nextInt(chars.length)]; }
    private boolean isFirebaseLinked(UserEntity user) { return user.getFirebaseUid() != null && !user.getFirebaseUid().isBlank(); }
    private String fullName(UserEntity user) { return (user.getFirstName() + " " + user.getLastName()).trim(); }
}
