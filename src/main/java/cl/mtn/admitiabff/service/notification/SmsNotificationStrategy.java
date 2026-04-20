package cl.mtn.admitiabff.service.notification;

import cl.mtn.admitiabff.domain.common.NotificationChannel;
import cl.mtn.admitiabff.domain.common.NotificationStatus;
import cl.mtn.admitiabff.domain.notification.NotificationEntity;
import cl.mtn.admitiabff.util.JsonSupport;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SmsNotificationStrategy implements NotificationChannelStrategy {
    private final JsonSupport jsonSupport;

    public SmsNotificationStrategy(JsonSupport jsonSupport) {
        this.jsonSupport = jsonSupport;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public NotificationEntity createNotification(Map<String, Object> payload) {
        NotificationEntity entity = new NotificationEntity();
        entity.setRecipientType((String) payload.get("recipientType"));
        entity.setRecipientId(payload.get("recipientId") instanceof Number number ? number.longValue() : null);
        entity.setRecipient(String.valueOf(payload.get("to")));
        entity.setChannel(NotificationChannel.SMS);
        entity.setType(String.valueOf(payload.getOrDefault("type", "SMS")));
        entity.setMessage((String) payload.get("message"));
        entity.setTemplateData(jsonSupport.write(Map.of()));
        entity.setStatus(NotificationStatus.SENT);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    @Override
    public void dispatch(NotificationEntity notification) {
        // Placeholder: external SMS provider integration lives behind this strategy.
    }
}
