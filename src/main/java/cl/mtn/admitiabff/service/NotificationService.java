package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.NotificationChannel;
import cl.mtn.admitiabff.domain.common.NotificationStatus;
import cl.mtn.admitiabff.domain.notification.NotificationEntity;
import cl.mtn.admitiabff.repository.NotificationRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.notification.NotificationChannelStrategy;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio de notificaciones (persistencia + dispatcher por canal).
 *
 * <p><b>NO es punto de entrada para envío de emails.</b> El único punto público
 * para emails es {@code EmailComposerService}; este servicio sólo expone:
 * <ul>
 *   <li>CRUD/consulta de notificaciones persistidas.</li>
 *   <li>{@link #email(Map)}: usado <i>internamente</i> por {@code EmailComposerService}
 *       para persistir + despachar la {@code NotificationEntity}.</li>
 *   <li>SMS (canal alterno).</li>
 *   <li>Helpers de configuración / verificación de email.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final Map<NotificationChannel, NotificationChannelStrategy> strategies;

    public NotificationService(NotificationRepository notificationRepository,
                               UserRepository userRepository,
                               List<NotificationChannelStrategy> strategies) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.strategies = new EnumMap<>(NotificationChannel.class);
        strategies.forEach(strategy -> this.strategies.put(strategy.channel(), strategy));
    }

    public Map<String, Object> list(String recipientType, Long recipientId, String channel, String type, String status, int page, int limit) {
        Page<NotificationEntity> result = notificationRepository.search(
                emptyToNull(recipientType), recipientId,
                channel == null || channel.isBlank() ? null : NotificationChannel.valueOf(channel.toUpperCase()),
                emptyToNull(type),
                status == null || status.isBlank() ? null : NotificationStatus.valueOf(status.toUpperCase()),
                PageRequest.of(page, limit));
        return Map.of(
                "content", result.getContent().stream().map(this::toResponse).toList(),
                "number", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "first", result.isFirst(),
                "last", result.isLast(),
                "numberOfElements", result.getNumberOfElements(),
                "empty", result.isEmpty());
    }

    public Map<String, Object> get(Long id) {
        return toResponse(notificationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notificación no encontrada")));
    }

    /**
     * Persistencia + dispatch del email. <b>Uso interno</b>: invocado únicamente
     * desde {@code EmailComposerService} después de renderizar el template.
     * No invocar desde controllers/services de negocio: usar {@code EmailComposerService}.
     */
    @Transactional
    public Map<String, Object> email(Map<String, Object> payload) {
        return dispatch(NotificationChannel.EMAIL, payload);
    }

    @Transactional
    public Map<String, Object> sms(Map<String, Object> payload) {
        return dispatch(NotificationChannel.SMS, payload);
    }

    @Transactional
    public Map<String, Object> bulkSms(Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recipients = (List<Map<String, Object>>) payload.getOrDefault("recipients", List.of());
        List<Map<String, Object>> data = recipients.stream().map(this::sms).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        notificationRepository.deleteById(id);
        return Map.of("success", true, "message", "Notificación eliminada");
    }

    public Map<String, Object> configStatus() {
        boolean mockMode = Boolean.parseBoolean(System.getenv().getOrDefault("APP_EMAIL_MOCK_MODE", "false"));
        String from = System.getenv().getOrDefault("APP_EMAIL_FROM", "");
        boolean resendConfigured = !System.getenv().getOrDefault("RESEND_API_KEY", "").isBlank();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("provider", "resend");
        result.put("mockMode", mockMode);
        result.put("from", from);
        result.put("resendConfigured", resendConfigured);
        result.put("environment", System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "default"));
        return result;
    }

    public Map<String, Object> checkEmailExists(String email) {
        return Map.of("exists", userRepository.existsByEmailIgnoreCase(email), "email", email);
    }

    private Map<String, Object> dispatch(NotificationChannel channel, Map<String, Object> payload) {
        NotificationChannelStrategy strategy = strategies.get(channel);
        NotificationEntity entity = strategy.createNotification(payload);
        strategy.dispatch(entity);
        return Map.of(
                "success", true,
                "message", channel == NotificationChannel.EMAIL ? "Email procesado" : "SMS procesado",
                "data", toResponse(notificationRepository.save(entity)));
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
