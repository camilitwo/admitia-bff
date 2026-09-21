package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.realtime.PrekinderRealtimeNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class PrekinderFlowServiceFamilyTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PrekinderFlowService service = new PrekinderFlowService(jdbc,
        mock(PlatformTransactionManager.class), mock(PrekinderAccessService.class),
        mock(PrekinderProfessionalAccountService.class), mock(EnvelopeEncryptionService.class),
        new ObjectMapper(), mock(PrekinderRealtimeNotifier.class));

    @Test
    void reusesExistingFamilyForSecondApplicant() {
        UUID familyId = UUID.randomUUID();
        PrekinderActor actor = new PrekinderActor(UUID.randomUUID(), 173L, "APODERADO");
        when(jdbc.queryForList(anyString(), anyMap(), eq(UUID.class))).thenReturn(List.of(familyId));

        assertThat(service.resolveFamily(actor)).isEqualTo(familyId);

        verify(jdbc, never()).queryForObject(contains("INSERT INTO families"), anyMap(), eq(UUID.class));
    }

    @Test
    void createsFamilyOnlyWhenActorDoesNotHaveOne() {
        PrekinderActor actor = new PrekinderActor(UUID.randomUUID(), 173L, "APODERADO");
        when(jdbc.queryForList(anyString(), anyMap(), eq(UUID.class))).thenReturn(List.of());
        UUID insertedFamilyId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("INSERT INTO families"), anyMap(), eq(UUID.class)))
            .thenReturn(insertedFamilyId);

        UUID familyId = service.resolveFamily(actor);

        assertThat(familyId).isEqualTo(insertedFamilyId);
        verify(jdbc).queryForObject(contains("ON CONFLICT (external_reference)"), anyMap(), eq(UUID.class));
    }
}
