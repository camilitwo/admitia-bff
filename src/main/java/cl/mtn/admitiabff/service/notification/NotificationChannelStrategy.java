package cl.mtn.admitiabff.service.notification;

import cl.mtn.admitiabff.domain.common.NotificationChannel;
import cl.mtn.admitiabff.domain.notification.NotificationEntity;
import java.util.Map;

public interface NotificationChannelStrategy {
    NotificationChannel channel();
    NotificationEntity createNotification(Map<String, Object> payload);
    void dispatch(NotificationEntity notification);
}
