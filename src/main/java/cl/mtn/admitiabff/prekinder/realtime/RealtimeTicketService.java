package cl.mtn.admitiabff.prekinder.realtime;

import cl.mtn.admitiabff.config.AuthContext;
import cl.mtn.admitiabff.prekinder.config.PrekinderProperties;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.service.PrekinderAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class RealtimeTicketService {
    private static final String PREFIX = "prekinder:ticket:";
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PrekinderProperties properties;
    private final PrekinderAccessService access;
    private final SecureRandom random = new SecureRandom();

    public RealtimeTicketService(StringRedisTemplate redis, ObjectMapper objectMapper,
                                 PrekinderProperties properties, PrekinderAccessService access) {
        this.redis = redis; this.objectMapper = objectMapper; this.properties = properties; this.access = access;
    }

    public IssuedTicket issue(String origin) {
        assertAllowedOrigin(origin);
        PrekinderActor actor = access.requireActor();
        var auth = AuthContext.get();
        byte[] value = new byte[32];
        random.nextBytes(value);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        TicketBinding binding = new TicketBinding(actor.id().toString(), String.valueOf(actor.legacyUserId()), actor.role(),
            auth.sessionId(), origin, "prekinder-realtime", Instant.now().plus(properties.realtime().ticketTtl()).getEpochSecond());
        try {
            redis.opsForValue().set(PREFIX + hash(ticket), objectMapper.writeValueAsString(binding), properties.realtime().ticketTtl());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No fue posible emitir el ticket", exception);
        }
        return new IssuedTicket(ticket, properties.realtime().ticketTtl().toSeconds());
    }

    public Optional<TicketBinding> consume(String ticket, String origin) {
        if (ticket == null || ticket.length() < 40) return Optional.empty();
        String json = redis.opsForValue().getAndDelete(PREFIX + hash(ticket));
        if (json == null) return Optional.empty();
        try {
            TicketBinding binding = objectMapper.readValue(json, TicketBinding.class);
            if (!MessageDigest.isEqual(binding.origin().getBytes(StandardCharsets.UTF_8), origin.getBytes(StandardCharsets.UTF_8))
                    || !"prekinder-realtime".equals(binding.audience()) || binding.expiresAt() < Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(binding);
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    public void assertAllowedOrigin(String origin) {
        if (origin == null || !properties.realtime().allowedOrigins().contains(origin)) {
            throw new SecurityException("Origin no permitido");
        }
    }

    private static String hash(String ticket) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record IssuedTicket(String ticket, long expiresInSeconds) {}
    public record TicketBinding(String actorId, String legacyUserId, String role, String sessionId,
                                String origin, String audience, long expiresAt) {}
}
