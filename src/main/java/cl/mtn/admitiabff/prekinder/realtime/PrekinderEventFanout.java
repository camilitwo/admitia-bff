package cl.mtn.admitiabff.prekinder.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderEventFanout implements MessageListener {
    public static final String CHANNEL = "prekinder:events";
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final PrekinderPresenceRegistry presence;
    private final SimpMessagingTemplate messaging;

    public PrekinderEventFanout(StringRedisTemplate redis, ObjectMapper mapper,
                                PrekinderPresenceRegistry presence, SimpMessagingTemplate messaging) {
        this.redis = redis; this.mapper = mapper; this.presence = presence; this.messaging = messaging;
    }

    public void publish(MinimalEvent event) {
        try { redis.convertAndSend(CHANNEL, mapper.writeValueAsString(event)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Evento inválido", exception);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            MinimalEvent event = mapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), MinimalEvent.class);
            for (String actorId : presence.watchers(event.entityId())) {
                messaging.convertAndSendToUser(actorId, "/queue/prekinder/events", event);
            }
        } catch (Exception ignored) {
            // Nunca se registra el cuerpo Redis: podría provenir de un publicador no confiable.
        }
    }

    public record MinimalEvent(UUID eventId, UUID entityId, long sequence, String eventType) {}
}
