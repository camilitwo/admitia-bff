package cl.mtn.admitiabff.prekinder.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderSocketLifecycle {
    private final StringRedisTemplate redis;
    public PrekinderSocketLifecycle(StringRedisTemplate redis) { this.redis = redis; }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        if (event.getUser() instanceof PrekinderPrincipal principal) {
            redis.opsForValue().decrement("prekinder:connections:" + principal.name());
        }
    }
}
