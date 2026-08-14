package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.config.AuthContext;
import cl.mtn.admitiabff.config.AuthUser;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.repository.PrekinderActorRepository;
import cl.mtn.admitiabff.prekinder.security.PrekinderAuthContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class PrekinderAccessServiceTest {
    private final PrekinderActorRepository actors = mock(PrekinderActorRepository.class);
    private final PrekinderAccessService access = new PrekinderAccessService(actors);

    @AfterEach
    void clearAuthentication() {
        AuthContext.clear();
        PrekinderAuthContext.clear();
    }

    @Test
    void allowsOnlyAdminAndRegistersItsActor() {
        UUID actorId = UUID.randomUUID();
        PrekinderActor actor = new PrekinderActor(actorId, 42L, "ADMIN");
        AuthContext.set(new AuthUser(42L, "admin@mtn.cl", "ADMIN", "session-1"));
        when(actors.upsert(42L, "ADMIN")).thenReturn(actor);

        assertThat(access.requireActor()).isSameAs(actor);
        verify(actors).upsert(42L, "ADMIN");
    }

    @Test
    void allowsEvaluatorIntoModuleButRejectsSensitiveAdministration() {
        PrekinderActor actor = new PrekinderActor(UUID.randomUUID(), 7L, "TEACHER");
        AuthContext.set(new AuthUser(7L, "teacher@mtn.cl", "TEACHER", "session-2"));
        when(actors.upsert(7L, "TEACHER")).thenReturn(actor);

        assertThat(access.requireActor()).isSameAs(actor);
        assertThatThrownBy(access::requireSensitiveAccess)
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("Permiso administrativo requerido");
    }

    @Test
    void allowsGenericPrekinderProfessionalToUseEvaluatorWorkspace() {
        PrekinderActor actor = new PrekinderActor(UUID.randomUUID(), 73L, "PREKINDER_PROFESSIONAL");
        PrekinderAuthContext.set(new PrekinderAuthContext.Principal(
            actor, "legacy:73", "professional@mtn.cl", "session-professional"));

        assertThat(access.requireEvaluator()).isSameAs(actor);
    }

    @Test
    void rejectsMissingAuthentication() {
        assertThatThrownBy(access::requireActor)
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("Sin acceso al módulo Prekínder");
    }
}
