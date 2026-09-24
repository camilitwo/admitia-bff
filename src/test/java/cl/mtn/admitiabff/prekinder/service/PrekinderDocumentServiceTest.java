package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.service.VercelBlobService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class PrekinderDocumentServiceTest {
    private NamedParameterJdbcTemplate jdbc;
    private PrekinderDocumentService service;
    private UUID applicationId;
    private PrekinderActor guardian;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        service = new PrekinderDocumentService(jdbc, mock(PrekinderAccessService.class),
            mock(VercelBlobService.class), mock(PrekinderApplicationStateService.class), "uploads");
        applicationId = UUID.randomUUID();
        guardian = new PrekinderActor(UUID.randomUUID(), 10L, "GUARDIAN");
        when(jdbc.queryForObject(
            "SELECT status FROM applications WHERE application_id = :id",
            Map.of("id", applicationId), String.class)).thenReturn("FORM_PENDING");
    }

    @Test
    void permitsConfiguredBirthCertificateDuringInitialStage() {
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("config.required_documents"),
            org.mockito.ArgumentMatchers.eq(Map.of("id", applicationId, "category", "BIRTH_CERTIFICATE")),
            org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(1L);

        assertThatCode(() -> service.validateUploadScope(applicationId, "BIRTH_CERTIFICATE", guardian))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsCategoryNotRequiredByProcess() {
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("config.required_documents"),
            org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(0L);

        assertThatThrownBy(() -> service.validateUploadScope(applicationId, "MEDICAL_CERTIFICATE", guardian))
            .isInstanceOf(PrekinderDomainException.class)
            .hasMessageContaining("no es requerida");
    }

    @Test
    void permitsCategoryExplicitlyEnabledForCorrection() {
        when(jdbc.queryForObject(
            "SELECT status FROM applications WHERE application_id = :id",
            Map.of("id", applicationId), String.class)).thenReturn("REQUIRES_INFORMATION");
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("application_correction_requests"),
            org.mockito.ArgumentMatchers.eq(Map.of("id", applicationId, "category", "MEDICAL_CERTIFICATE")),
            org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(1L);

        assertThatCode(() -> service.validateUploadScope(applicationId, "MEDICAL_CERTIFICATE", guardian))
            .doesNotThrowAnyException();
    }

    @Test
    void administrativeRolesKeepUnrestrictedUploadAccess() {
        var admin = new PrekinderActor(UUID.randomUUID(), 11L, "ADMIN");

        assertThatCode(() -> service.validateUploadScope(applicationId, "MEDICAL_CERTIFICATE", admin))
            .doesNotThrowAnyException();
    }
}
