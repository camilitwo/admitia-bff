package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.AdmissionCycleEntity;
import cl.mtn.admitiabff.domain.common.AdmissionCycleStatus;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.repository.AdmissionCycleRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AdmissionCycleServiceSafetyTest {
    private AdmissionCycleRepository repository;
    private JdbcTemplate jdbcTemplate;
    private AuthService authService;
    private AuthService.AuthContextHolder admin;

    @BeforeEach
    void setUp() {
        repository = mock(AdmissionCycleRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        authService = mock(AuthService.class);
        admin = new AuthService.AuthContextHolder(7L, "admin@example.cl", Role.ADMIN.name());
        when(authService.requireAuth()).thenReturn(admin);
    }

    @Test
    void rejectsNonAdminBeforeReadingOrWritingCycleData() {
        when(authService.hasAnyRoleContext(admin, Role.ADMIN)).thenReturn(false);
        AdmissionCycleService service = service(true, true, false, "re_live", "admision@example.cl");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.close(2027, "terminar postulacion año 2027"));

        assertEquals(403, exception.getStatusCode().value());
        verify(repository, never()).findByAcademicYearForUpdate(2027);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsCloseWhenEitherOperationalFlagIsDisabled() {
        when(authService.hasAnyRoleContext(admin, Role.ADMIN)).thenReturn(true);
        AdmissionCycleService service = service(true, false, false, "re_live", "admision@example.cl");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.close(2027, "terminar postulacion año 2027"));

        assertEquals(503, exception.getStatusCode().value());
        verify(repository, never()).findByAcademicYearForUpdate(2027);
    }

    @Test
    void rejectsMockEmailProviderBeforeLockingCycle() {
        when(authService.hasAnyRoleContext(admin, Role.ADMIN)).thenReturn(true);
        AdmissionCycleService service = service(true, true, true, "re_live", "admision@example.cl");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.close(2027, "terminar postulacion año 2027"));

        assertEquals(503, exception.getStatusCode().value());
        verify(repository, never()).findByAcademicYearForUpdate(2027);
    }

    @Test
    void rejectsIncorrectPhraseBeforePreflightOrQueueInsert() {
        when(authService.hasAnyRoleContext(admin, Role.ADMIN)).thenReturn(true);
        AdmissionCycleEntity cycle = new AdmissionCycleEntity();
        cycle.setId(11L);
        cycle.setAcademicYear(2027);
        cycle.setStatus(AdmissionCycleStatus.OPEN);
        when(repository.findByAcademicYearForUpdate(2027)).thenReturn(Optional.of(cycle));
        AdmissionCycleService service = service(true, true, false, "re_live", "admision@example.cl");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.close(2027, "terminar postulacion año 2026"));

        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void retryNeverIncludesAmbiguousDeliveries() {
        when(authService.hasAnyRoleContext(admin, Role.ADMIN)).thenReturn(true);
        AdmissionCycleEntity cycle = new AdmissionCycleEntity();
        cycle.setId(11L);
        cycle.setAcademicYear(2027);
        cycle.setStatus(AdmissionCycleStatus.CLOSED_WITH_ERRORS);
        when(repository.findByAcademicYearForUpdate(2027)).thenReturn(Optional.of(cycle));
        when(jdbcTemplate.update(anyString(), eq(11L))).thenReturn(0);
        AdmissionCycleService service = service(true, true, false, "re_live", "admision@example.cl");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.retryFailed(2027));

        assertEquals(409, exception.getStatusCode().value());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(11L));
        assertTrue(sql.getValue().contains("status = 'FAILED'"));
        assertFalse(sql.getValue().contains("'UNKNOWN'"));
    }

    private AdmissionCycleService service(boolean closeEnabled, boolean dispatchEnabled, boolean mockMode,
                                          String apiKey, String from) {
        return new AdmissionCycleService(
                repository, jdbcTemplate, authService,
                closeEnabled, dispatchEnabled, mockMode, apiKey, from);
    }
}
