package cl.mtn.admitiabff.service.notification;

import cl.mtn.admitiabff.domain.common.NotificationChannel;
import cl.mtn.admitiabff.domain.common.NotificationStatus;
import cl.mtn.admitiabff.domain.notification.NotificationEntity;
import cl.mtn.admitiabff.util.JsonSupport;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class EmailNotificationStrategy implements NotificationChannelStrategy {
    private final JavaMailSender mailSender;
    private final JsonSupport jsonSupport;
    private final boolean mockMode;

    public EmailNotificationStrategy(JavaMailSender mailSender, JsonSupport jsonSupport, @Value("${app.email.mock-mode}") boolean mockMode) {
        this.mailSender = mailSender;
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
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(notification.getRecipient());
        message.setSubject(notification.getSubject());
        message.setText(notification.getMessage());
        mailSender.send(message);
    }
}
