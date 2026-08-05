package cl.mtn.admitiabff.prekinder.realtime;

import cl.mtn.admitiabff.prekinder.config.PrekinderProperties;
import java.time.Duration;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderStompInterceptor implements ChannelInterceptor {
    private static final Set<String> SUBSCRIPTIONS = Set.of(
        "/user/queue/prekinder/events", "/user/queue/prekinder/acks", "/user/queue/prekinder/errors"
    );
    private final RealtimeTicketService tickets;
    private final StringRedisTemplate redis;
    private final PrekinderProperties properties;
    private final PrekinderSocketRegistry sockets;

    public PrekinderStompInterceptor(RealtimeTicketService tickets, StringRedisTemplate redis,
                                     PrekinderProperties properties, PrekinderSocketRegistry sockets) {
        this.tickets = tickets; this.redis = redis; this.properties = properties; this.sockets = sockets;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;
        if (accessor.getCommand() == StompCommand.CONNECT) authenticate(accessor);
        else if (accessor.getCommand() == StompCommand.SUBSCRIBE) authorizeSubscription(accessor);
        else if (accessor.getCommand() == StompCommand.SEND) authorizeSend(accessor);
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String origin = String.valueOf(accessor.getSessionAttributes().get("prekinderOrigin"));
        String ticket = accessor.getFirstNativeHeader("X-Prekinder-Ticket");
        var binding = tickets.consume(ticket, origin).orElseThrow(() -> new SecurityException("Ticket inválido o reutilizado"));
        String connectionsKey = "prekinder:connections:" + binding.actorId();
        Long connections = redis.opsForValue().increment(connectionsKey);
        redis.expire(connectionsKey, Duration.ofMinutes(20));
        if (connections != null && connections > properties.realtime().maxConnectionsPerUser()) {
            redis.opsForValue().decrement(connectionsKey);
            throw new SecurityException("Límite de conexiones excedido");
        }
        accessor.getSessionAttributes().put("prekinderBinding", binding);
        accessor.setUser(new PrekinderPrincipal(binding.actorId()));
        sockets.bind(accessor.getSessionId(), binding.sessionId());
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        requireAuthenticated(accessor);
        if (!SUBSCRIPTIONS.contains(accessor.getDestination())) throw new SecurityException("Destino no permitido");
    }

    private void authorizeSend(StompHeaderAccessor accessor) {
        requireAuthenticated(accessor);
        if (!"/app/prekinder/operations".equals(accessor.getDestination())) throw new SecurityException("Destino no permitido");
        var binding = (RealtimeTicketService.TicketBinding) accessor.getSessionAttributes().get("prekinderBinding");
        String key = "prekinder:rate:" + binding.actorId() + ":" + (System.currentTimeMillis() / properties.realtime().rateWindow().toMillis());
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, properties.realtime().rateWindow().plusSeconds(1));
        if (count != null && count > properties.realtime().operationsPerWindow()) throw new SecurityException("Tasa excedida");
    }

    private static void requireAuthenticated(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof PrekinderPrincipal)) throw new SecurityException("Socket no autenticado");
    }
}
