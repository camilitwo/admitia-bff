package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.config.AuthContext;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.repository.PrekinderActorRepository;
import cl.mtn.admitiabff.prekinder.security.PrekinderAuthContext;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderAccessService {
    private static final String ADMIN_ROLE = "ADMIN";
    private static final Set<String> MODULE_ROLES = Set.of(
        "ADMIN", "COORDINATOR", "CYCLE_DIRECTOR", "TEACHER", "PSYCHOLOGIST", "INTERVIEWER", "EVALUATOR", "APODERADO",
        "PK_ADMIN", "PK_COORDINATOR", "PK_RECEPTION", "PK_DATA_ENTRY", "PK_REVIEWER", "PK_COMMITTEE",
        "PK_FINAL_APPROVER", "PK_AUDITOR", "PK_EVALUATOR_ACADEMIC", "PK_EVALUATOR_PSYCHOMOTOR",
        "PK_EVALUATOR_PSYCHOLOGY", "PK_EVALUATOR_ENTRY_INDICATORS", "PK_EVALUATOR_GROUP_OBSERVATION",
        "PK_EVALUATOR_LEARNING_SUPPORT", "PK_EVALUATOR_DAP", "PREKINDER_ACADEMIC", "PREKINDER_PSYCHOMOTOR",
        "PREKINDER_PSYCHOLOGY", "PREKINDER_INDICATORS", "PREKINDER_OBSERVER", "PREKINDER_SUPPORT", "PREKINDER_DAP"
    );
    private static final Set<String> EVALUATOR_ROLES = Set.of(
        "ADMIN", "COORDINATOR", "CYCLE_DIRECTOR", "TEACHER", "PSYCHOLOGIST", "INTERVIEWER", "EVALUATOR", "PK_ADMIN",
        "PK_EVALUATOR_ACADEMIC", "PK_EVALUATOR_PSYCHOMOTOR", "PK_EVALUATOR_PSYCHOLOGY",
        "PK_EVALUATOR_ENTRY_INDICATORS", "PK_EVALUATOR_GROUP_OBSERVATION", "PK_EVALUATOR_LEARNING_SUPPORT",
        "PK_EVALUATOR_DAP", "PREKINDER_ACADEMIC", "PREKINDER_PSYCHOMOTOR", "PREKINDER_PSYCHOLOGY",
        "PREKINDER_INDICATORS", "PREKINDER_OBSERVER", "PREKINDER_SUPPORT", "PREKINDER_DAP"
    );
    private final PrekinderActorRepository actors;

    public PrekinderAccessService(PrekinderActorRepository actors) { this.actors = actors; }

    public PrekinderActor requireActor() {
        var isolated = PrekinderAuthContext.get();
        if (isolated != null && isolated.actor() != null && MODULE_ROLES.contains(isolated.actor().role())) {
            return isolated.actor();
        }
        var auth = AuthContext.get();
        if (auth == null || auth.id() == null || !MODULE_ROLES.contains(auth.role())) {
            throw new AccessDeniedException("Sin acceso al módulo Prekínder");
        }
        return actors.upsert(auth.id(), auth.role());
    }

    public PrekinderActor requireSensitiveAccess() {
        return requireAdmin();
    }

    public PrekinderActor requireAdmin() {
        PrekinderActor actor = requireActor();
        if (!Set.of("ADMIN", "COORDINATOR", "CYCLE_DIRECTOR", "PK_ADMIN", "PK_COORDINATOR").contains(actor.role())) {
            throw new AccessDeniedException("Permiso administrativo requerido");
        }
        return actor;
    }

    public PrekinderActor requireSuperAdmin() {
        PrekinderActor actor = requireActor();
        if (!Set.of(ADMIN_ROLE, "PK_ADMIN").contains(actor.role())) {
            throw new AccessDeniedException("Permiso especial de administración requerido");
        }
        return actor;
    }

    public PrekinderActor requireEvaluator() {
        PrekinderActor actor = requireActor();
        if (!EVALUATOR_ROLES.contains(actor.role())) throw new AccessDeniedException("Permiso de evaluación requerido");
        return actor;
    }

    public PrekinderActor requireOperations() {
        PrekinderActor actor = requireActor();
        if (!Set.of("ADMIN", "COORDINATOR", "CYCLE_DIRECTOR", "PK_ADMIN", "PK_COORDINATOR", "PK_RECEPTION")
                .contains(actor.role())) {
            throw new AccessDeniedException("Permiso operativo requerido");
        }
        return actor;
    }
}
