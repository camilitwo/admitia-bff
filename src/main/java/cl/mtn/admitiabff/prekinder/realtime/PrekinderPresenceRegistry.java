package cl.mtn.admitiabff.prekinder.realtime;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderPresenceRegistry {
    private final ConcurrentHashMap<UUID, Set<String>> watchers = new ConcurrentHashMap<>();
    public void watch(UUID evaluationId, String actorId) {
        watchers.computeIfAbsent(evaluationId, ignored -> ConcurrentHashMap.newKeySet()).add(actorId);
    }
    public Set<String> watchers(UUID evaluationId) { return Set.copyOf(watchers.getOrDefault(evaluationId, Set.of())); }
}
