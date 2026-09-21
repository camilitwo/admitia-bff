package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class PrekinderGuardianFormServiceTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PrekinderAccessService access = mock(PrekinderAccessService.class);
    private final PrekinderGuardianFormService service = new PrekinderGuardianFormService(jdbc,
        mock(PlatformTransactionManager.class), access, mock(EnvelopeEncryptionService.class),
        new ObjectMapper(), mock(PrekinderApplicationStateService.class));

    @Test
    void familyFormRemainsEditableOnlyWhileTheProcessIsOpen() {
        assertThat(PrekinderGuardianFormService.canEditFamilyForm(true)).isTrue();
        assertThat(PrekinderGuardianFormService.canEditFamilyForm(false)).isFalse();
    }
}
