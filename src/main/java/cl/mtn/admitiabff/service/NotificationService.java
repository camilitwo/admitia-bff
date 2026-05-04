package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.NotificationChannel;
import cl.mtn.admitiabff.domain.common.NotificationStatus;
import cl.mtn.admitiabff.domain.notification.NotificationEntity;
import cl.mtn.admitiabff.repository.NotificationRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.notification.NotificationChannelStrategy;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@Service
@Transactional(readOnly = true)
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final Map<NotificationChannel, NotificationChannelStrategy> strategies;
    private final AuthService authService;

    public NotificationService(NotificationRepository notificationRepository, UserRepository userRepository, List<NotificationChannelStrategy> strategies, AuthService authService) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.authService = authService;
        this.strategies = new EnumMap<>(NotificationChannel.class);
        strategies.forEach(strategy -> this.strategies.put(strategy.channel(), strategy));
    }

    public Map<String, Object> list(String recipientType, Long recipientId, String channel, String type, String status, int page, int limit) {
        Page<NotificationEntity> result = notificationRepository.search(emptyToNull(recipientType), recipientId, channel == null || channel.isBlank() ? null : NotificationChannel.valueOf(channel.toUpperCase()), emptyToNull(type), status == null || status.isBlank() ? null : NotificationStatus.valueOf(status.toUpperCase()), PageRequest.of(page, limit));
        return Map.of("content", result.getContent().stream().map(this::toResponse).toList(), "number", result.getNumber(), "size", result.getSize(), "totalElements", result.getTotalElements(), "totalPages", result.getTotalPages(), "first", result.isFirst(), "last", result.isLast(), "numberOfElements", result.getNumberOfElements(), "empty", result.isEmpty());
    }

    public Map<String, Object> get(Long id) {
        return toResponse(notificationRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Notificación no encontrada")));
    }

    @Transactional
    public Map<String, Object> email(Map<String, Object> payload) {
        return dispatch(NotificationChannel.EMAIL, payload);
    }

    @Transactional
    public Map<String, Object> sms(Map<String, Object> payload) {
        return dispatch(NotificationChannel.SMS, payload);
    }

    @Transactional
    public Map<String, Object> bulkEmail(Map<String, Object> payload) {
        List<Map<String, Object>> data = ((List<Map<String, Object>>) payload.getOrDefault("recipients", List.of())).stream().map(this::email).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    @Transactional
    public Map<String, Object> bulkSms(Map<String, Object> payload) {
        List<Map<String, Object>> data = ((List<Map<String, Object>>) payload.getOrDefault("recipients", List.of())).stream().map(this::sms).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        notificationRepository.deleteById(id);
        return Map.of("success", true, "message", "Notificación eliminada");
    }

    public Map<String, Object> configStatus() {
        boolean mockMode = Boolean.parseBoolean(System.getenv().getOrDefault("APP_EMAIL_MOCK_MODE", "false"));
        String provider = System.getenv().getOrDefault("APP_EMAIL_PROVIDER", "ses");
        String from = System.getenv().getOrDefault("APP_EMAIL_FROM", "");
        String region = System.getenv().getOrDefault("AWS_SES_REGION", "");
        boolean sesConfigured = !System.getenv().getOrDefault("AWS_SES_ACCESS_KEY", "").isBlank()
                && !System.getenv().getOrDefault("AWS_SES_SECRET_KEY", "").isBlank();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("provider", provider);
        result.put("mockMode", mockMode);
        result.put("from", from);
        result.put("sesConfigured", sesConfigured);
        result.put("sesRegion", region);
        result.put("environment", System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "default"));
        return result;
    }

    public Map<String, Object> checkEmailExists(String email) {
        return Map.of("exists", userRepository.existsByEmailIgnoreCase(email), "email", email);
    }

    @Transactional
    public Map<String, Object> sendVerification(Map<String, Object> payload) {
        String email = String.valueOf(payload.get("email")).toLowerCase();
        String rut = payload.get("rut") == null ? null : String.valueOf(payload.get("rut"));
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("El email ya está registrado");
        }
        if (rut != null && userRepository.findAll().stream().anyMatch(user -> rut.equals(user.getRut()))) {
            throw new IllegalArgumentException("El RUT ya está registrado");
        }
        String code = String.format("%06d", new Random().nextInt(1_000_000));
        cl.mtn.admitiabff.domain.user.EmailVerificationCodeEntity entity = new cl.mtn.admitiabff.domain.user.EmailVerificationCodeEntity();
        entity.setEmail(email);
        entity.setCode(code);
        entity.setRut(rut);
        entity.setUsed(false);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        authService.findValidVerificationCode(email, "never-match");
        // previous line intentionally not used? no
        return email(Map.of("to", email, "subject", "Código de verificación", "message", "Tu código es: " + code, "type", "EMAIL_VERIFICATION"));
    }

    @Transactional
    public Map<String, Object> verifyCode(Map<String, Object> payload) {
        var verification = authService.findValidVerificationCode(String.valueOf(payload.get("email")), String.valueOf(payload.get("code")));
        verification.setUsed(true);
        return Map.of("isValid", true, "verified", true, "email", verification.getEmail());
    }

    public Map<String, Object> sendTest(Map<String, Object> payload) {
        return email(Map.of("to", payload.get("to"), "subject", payload.getOrDefault("subject", "Correo de prueba"), "message", payload.getOrDefault("message", "Correo de prueba del sistema MTN"), "type", "TEST"));
    }

    public Map<String, Object> institutional(String type, Long resourceId, Map<String, Object> payload) {
        Map<String, Object> request = new LinkedHashMap<>(payload);
        request.putIfAbsent("to", payload.getOrDefault("recipientEmail", "admision@mtn.cl"));
        request.putIfAbsent("subject", type + " #" + resourceId);
        request.putIfAbsent("message", payload.toString());
        request.put("type", type);
        return email(request);
    }

    @Transactional
    public Map<String, Object> recordEmail(Map<String, Object> payload) {
        return email(payload);
    }

    private Map<String, Object> dispatch(NotificationChannel channel, Map<String, Object> payload) {
        NotificationChannelStrategy strategy = strategies.get(channel);
        NotificationEntity entity = strategy.createNotification(payload);
        strategy.dispatch(entity);
        return Map.of("success", true, "message", channel == NotificationChannel.EMAIL ? "Email procesado" : "SMS procesado", "data", toResponse(notificationRepository.save(entity)));
    }

    private Map<String, Object> toResponse(NotificationEntity entity) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entity.getId());
        response.put("recipientType", entity.getRecipientType());
        response.put("recipientId", entity.getRecipientId());
        response.put("recipient", entity.getRecipient());
        response.put("channel", entity.getChannel().name());
        response.put("type", entity.getType());
        response.put("subject", entity.getSubject());
        response.put("message", entity.getMessage());
        response.put("templateName", entity.getTemplateName());
        response.put("templateData", entity.getTemplateData());
        response.put("status", entity.getStatus().name());
        response.put("createdAt", entity.getCreatedAt());
        return response;
    }

    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
