package cl.mtn.admitiabff.service.notification;

import cl.mtn.admitiabff.domain.common.NotificationChannel;
import cl.mtn.admitiabff.domain.common.NotificationStatus;
import cl.mtn.admitiabff.domain.notification.NotificationEntity;
import cl.mtn.admitiabff.util.JsonSupport;
import java.time.LocalDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single email channel: Resend.
 * <p>Internal use only by {@code NotificationService} / {@code EmailComposerService}.
 * Other services must NOT inject this class directly: the only public entry
 * point for sending emails with templates is {@code EmailComposerService}.
 */
@Component
public class EmailNotificationStrategy implements NotificationChannelStrategy {
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationStrategy.class);

    private final ResendEmailSender resendEmailSender;
    private final JsonSupport jsonSupport;
    private final boolean mockMode;

    public EmailNotificationStrategy(ResendEmailSender resendEmailSender,
                                     JsonSupport jsonSupport,
                                     @Value("${app.email.mock-mode:false}") boolean mockMode) {
        this.resendEmailSender = resendEmailSender;
        this.jsonSupport = jsonSupport;
        this.mockMode = mockMode;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public NotificationEntity createNotification(Map<String, Object> payload) {
        NotificationEntity entity = new NotificationEntity();
        entity.setRecipientType((String) payload.get("recipientType"));
        entity.setRecipientId(payload.get("recipientId") instanceof Number number ? number.longValue() : null);
        entity.setRecipient(String.valueOf(payload.get("to")));
        entity.setChannel(NotificationChannel.EMAIL);
        entity.setType(String.valueOf(payload.getOrDefault("type", "EMAIL")));
        entity.setSubject((String) payload.get("subject"));
        boolean sensitive = Boolean.TRUE.equals(payload.get("sensitive"));
        String renderedMessage = (String) payload.get("message");
        entity.setMessage(sensitive ? "[CONTENIDO SENSIBLE OMITIDO]" : renderedMessage);
        entity.setDispatchMessage(sensitive ? renderedMessage : null);
        entity.setTemplateName((String) payload.get("templateName"));
        entity.setTemplateData(jsonSupport.write(sensitive ? Map.of("redacted", true) : payload.getOrDefault("templateData", Map.of())));
        entity.setStatus(NotificationStatus.SENT);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setIdempotencyKey((String) payload.get("idempotencyKey"));
        return entity;
    }

    @Override
    public void dispatch(NotificationEntity notification) {
        if (mockMode) {
            log.info("[MOCK] Email a {} - {}", notification.getRecipient(), notification.getSubject());
            notification.setDispatchMessage(null);
            return;
        }
        try {
            String message = notification.getDispatchMessage() != null
                    ? notification.getDispatchMessage()
                    : notification.getMessage();
            String messageId = notification.getIdempotencyKey() == null
                    ? resendEmailSender.send(notification.getRecipient(), notification.getSubject(), message)
                    : resendEmailSender.send(notification.getRecipient(), notification.getSubject(), message,
                        notification.getIdempotencyKey());
            notification.setProviderMessageId(messageId);
            notification.setDispatchMessage(null);
        } catch (Exception ex) {
            log.error("Error enviando email a {}: {}", notification.getRecipient(), ex.getMessage(), ex);
            notification.setStatus(NotificationStatus.FAILED);
            throw new RuntimeException("Error enviando email: " + ex.getMessage(), ex);
        }
    }
}
