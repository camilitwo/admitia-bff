package cl.mtn.admitiabff.prekinder.realtime;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderRevocationListener implements MessageListener {
    public static final String CHANNEL = "prekinder:revocations";
    private final StringRedisTemplate redis;
    private final PrekinderSocketRegistry sockets;
    public PrekinderRevocationListener(StringRedisTemplate redis, PrekinderSocketRegistry sockets) {
        this.redis = redis; this.sockets = sockets;
    }
    @EventListener public void local(SessionRevokedEvent event) {
        sockets.closeApplicationSession(event.sessionId());
        redis.convertAndSend(CHANNEL, event.sessionId());
    }
    @Override public void onMessage(Message message, byte[] pattern) {
        sockets.closeApplicationSession(new String(message.getBody(), StandardCharsets.UTF_8));
    }
}
