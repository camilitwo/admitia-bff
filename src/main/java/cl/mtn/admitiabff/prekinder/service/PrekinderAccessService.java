package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.config.AuthContext;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.repository.PrekinderActorRepository;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderAccessService {
    private static final Set<String> MODULE_ROLES = Set.of(
        "ADMIN", "COORDINATOR", "TEACHER", "PSYCHOLOGIST", "CYCLE_DIRECTOR", "INTERVIEWER"
    );
    private static final Set<String> SENSITIVE_ROLES = Set.of("ADMIN", "COORDINATOR", "PSYCHOLOGIST", "CYCLE_DIRECTOR");
    private final PrekinderActorRepository actors;

    public PrekinderAccessService(PrekinderActorRepository actors) { this.actors = actors; }

    public PrekinderActor requireActor() {
        var auth = AuthContext.get();
        if (auth == null || auth.id() == null || !MODULE_ROLES.contains(auth.role())) {
            throw new AccessDeniedException("Sin acceso al módulo Prekínder");
        }
        return actors.upsert(auth.id(), auth.role());
    }

    public PrekinderActor requireSensitiveAccess() {
        PrekinderActor actor = requireActor();
        if (!SENSITIVE_ROLES.contains(actor.role())) throw new AccessDeniedException("Permiso sensible requerido");
        return actor;
    }
}
