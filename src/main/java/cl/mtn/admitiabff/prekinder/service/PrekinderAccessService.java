package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.config.AuthContext;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.repository.PrekinderActorRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderAccessService {
    private static final String ADMIN_ROLE = "ADMIN";
    private final PrekinderActorRepository actors;

    public PrekinderAccessService(PrekinderActorRepository actors) { this.actors = actors; }

    public PrekinderActor requireActor() {
        var auth = AuthContext.get();
        if (auth == null || auth.id() == null || !ADMIN_ROLE.equals(auth.role())) {
            throw new AccessDeniedException("Sin acceso al módulo Prekínder");
        }
        return actors.upsert(auth.id(), auth.role());
    }

    public PrekinderActor requireSensitiveAccess() {
        PrekinderActor actor = requireActor();
        if (!ADMIN_ROLE.equals(actor.role())) throw new AccessDeniedException("Permiso sensible requerido");
        return actor;
    }
}
