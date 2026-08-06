package cl.mtn.admitiabff.prekinder.realtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.config.AuthContext;
import cl.mtn.admitiabff.config.AuthUser;
import cl.mtn.admitiabff.prekinder.config.PrekinderProperties;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.service.PrekinderAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RealtimeTicketServiceTest {
    @AfterEach void clear() { AuthContext.clear(); }

    @Test
    void storesOnlyHashAndConsumesTicketExactlyOnce() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        AtomicReference<String> storedKey = new AtomicReference<>();
        AtomicReference<String> storedJson = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            storedKey.set(invocation.getArgument(0)); storedJson.set(invocation.getArgument(1)); return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.getAndDelete(anyString())).thenAnswer(invocation -> storedJson.getAndSet(null));

        PrekinderAccessService access = mock(PrekinderAccessService.class);
        UUID actorId = UUID.randomUUID();
        when(access.requireActor()).thenReturn(new PrekinderActor(actorId, 42L, "ADMIN"));
        var properties = new PrekinderProperties(true, null, null,
            new PrekinderProperties.Realtime(List.of("https://admitia.cl"), Duration.ofSeconds(30),
                Duration.ofSeconds(20), 3, 16_384, 30, Duration.ofSeconds(10)), null);
        RealtimeTicketService service = new RealtimeTicketService(redis, new ObjectMapper(), properties, access);
        AuthContext.set(new AuthUser(42L, "persona@example.cl", "COORDINATOR", "session-1"));

        var issued = service.issue("https://admitia.cl");

        assertFalse(storedKey.get().contains(issued.ticket()));
        assertTrue(service.consume(issued.ticket(), "https://admitia.cl").isPresent());
        assertTrue(service.consume(issued.ticket(), "https://admitia.cl").isEmpty());
        verify(values).set(anyString(), anyString(), any(Duration.class));
    }
}
