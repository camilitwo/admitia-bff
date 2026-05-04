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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class EmailNotificationStrategy implements NotificationChannelStrategy {
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationStrategy.class);

    private final JavaMailSender mailSender;
    private final SesEmailSender sesEmailSender;
    private final JsonSupport jsonSupport;
    private final boolean mockMode;
    private final String provider;

    public EmailNotificationStrategy(JavaMailSender mailSender,
                                     SesEmailSender sesEmailSender,
                                     JsonSupport jsonSupport,
                                     @Value("${app.email.mock-mode}") boolean mockMode,
                                     @Value("${app.email.provider:ses}") String provider) {
        this.mailSender = mailSender;
        this.sesEmailSender = sesEmailSender;
        this.jsonSupport = jsonSupport;
        this.mockMode = mockMode;
        this.provider = provider;
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
        entity.setMessage((String) payload.get("message"));
        entity.setTemplateName((String) payload.get("templateName"));
        entity.setTemplateData(jsonSupport.write(payload.getOrDefault("templateData", Map.of())));
        entity.setStatus(NotificationStatus.SENT);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    @Override
    public void dispatch(NotificationEntity notification) {
        if (mockMode) {
            log.info("[MOCK] Email a {} - {}", notification.getRecipient(), notification.getSubject());
            return;
        }
        try {
            if ("ses".equalsIgnoreCase(provider)) {
                sesEmailSender.send(notification.getRecipient(), notification.getSubject(), notification.getMessage());
            } else {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setTo(notification.getRecipient());
                message.setSubject(notification.getSubject());
                message.setText(notification.getMessage());
                mailSender.send(message);
            }
        } catch (Exception ex) {
            log.error("Error enviando email a {}: {}", notification.getRecipient(), ex.getMessage(), ex);
            notification.setStatus(NotificationStatus.FAILED);
            throw new RuntimeException("Error enviando email: " + ex.getMessage(), ex);
        }
    }
}
