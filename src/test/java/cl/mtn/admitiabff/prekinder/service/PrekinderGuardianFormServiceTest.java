package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

class PrekinderGuardianFormServiceTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PrekinderAccessService access = mock(PrekinderAccessService.class);
    private final PrekinderGuardianFormService service = new PrekinderGuardianFormService(jdbc,
        mock(PlatformTransactionManager.class), access, mock(EnvelopeEncryptionService.class),
        new ObjectMapper(), mock(PrekinderApplicationStateService.class));

    @Test
    @SuppressWarnings("unchecked")
    void refusesToCompleteAgainASubmittedFamilyFormSharedBySiblings() {
        UUID applicationId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        when(access.requireActor()).thenReturn(new PrekinderActor(UUID.randomUUID(), 173L, "APODERADO"));
        when(jdbc.query(contains("a.payment_required"), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(ownedApplicationRow(familyId, processId), 0));
        });
        when(jdbc.queryForObject(contains("count(*) FROM applications candidate"), anyMap(), eq(Integer.class))).thenReturn(1);
        when(jdbc.query(contains("FOR UPDATE"), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(submittedFamilyFormRow(UUID.randomUUID()), 0));
        });
        when(jdbc.queryForObject(contains("application_correction_requests"), anyMap(), eq(Integer.class))).thenReturn(0);

        assertThatThrownBy(() -> service.save(applicationId,
            Map.of("isSubmitted", true, "familyValues", "Segundo envío")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("ya fue enviado");

        verify(jdbc, never()).update(contains("INSERT INTO prekinder_complementary_forms"), anyMap());
    }

    @Test
    void blocksRepeatCompletionOnlyWhenTheFormIsSubmittedAndNoCorrectionIsOpen() {
        assertThat(PrekinderGuardianFormService.blocksRepeatCompletion(true, false)).isTrue();
        assertThat(PrekinderGuardianFormService.blocksRepeatCompletion(true, true)).isFalse();
        assertThat(PrekinderGuardianFormService.blocksRepeatCompletion(false, false)).isFalse();
    }

    private static ResultSet ownedApplicationRow(UUID familyId, UUID processId) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getBoolean("payment_required")).thenReturn(true);
        when(row.getString("payment_status")).thenReturn("PAID");
        when(row.getObject("family_id", UUID.class)).thenReturn(familyId);
        when(row.getObject("process_id", UUID.class)).thenReturn(processId);
        when(row.getBoolean("process_open")).thenReturn(true);
        return row;
    }

    private static ResultSet submittedFamilyFormRow(UUID formId) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("form_id", UUID.class)).thenReturn(formId);
        when(row.getBoolean("submitted")).thenReturn(true);
        return row;
    }
}
