package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

class PrekinderInclusionServiceTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PrekinderAccessService access = mock(PrekinderAccessService.class);
    private final EnvelopeEncryptionService encryption = mock(EnvelopeEncryptionService.class);
    private final PrekinderInclusionService service = new PrekinderInclusionService(
        jdbc, transactions, access, encryption, new ObjectMapper());

    @Test
    void rejectsDirectCorrectionWhenActorDoesNotHaveSuperAdminRole() {
        UUID applicationId = UUID.randomUUID();
        when(access.requireSuperAdmin()).thenThrow(new AccessDeniedException("Permiso especial de administración requerido"));

        assertThatThrownBy(() -> service.correctDirectly(applicationId,
            Map.of("backgroundSummary", "Actualizado"), "Corrección", 1))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("Permiso especial de administración requerido");

        verify(access).requireSuperAdmin();
    }

    @Test
    void neverAllowsAdministrativeConsentChanges() {
        UUID applicationId = UUID.randomUUID();
        when(access.requireSuperAdmin()).thenReturn(new PrekinderActor(UUID.randomUUID(), 1L, "ADMIN"));

        assertThatThrownBy(() -> service.correctDirectly(applicationId,
            Map.of("consentAccepted", false), "No corresponde modificarlo", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("La corrección contiene campos no permitidos");
    }

    @Test
    void requiresReasonBeforeOpeningAnAdministrativeTransaction() {
        UUID applicationId = UUID.randomUUID();
        when(access.requireSuperAdmin()).thenReturn(new PrekinderActor(UUID.randomUUID(), 1L, "PK_ADMIN"));

        assertThatThrownBy(() -> service.correctDirectly(applicationId,
            Map.of("currentSupports", "Apoyo actualizado"), "  ", 2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("La corrección administrativa requiere motivo");
    }
}
