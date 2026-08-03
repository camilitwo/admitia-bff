package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.AdmissionCycleEntity;
import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.AdmissionCycleStatus;
import cl.mtn.admitiabff.repository.AdmissionCycleRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AdmissionCycleGuardTest {
    private AdmissionCycleRepository repository;
    private JdbcTemplate jdbcTemplate;
    private AdmissionCycleGuard guard;

    @BeforeEach
    void setUp() {
        repository = mock(AdmissionCycleRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        guard = new AdmissionCycleGuard(repository, jdbcTemplate);
    }

    @Test
    void createUsesIdempotentCycleInsertAndLocksOpenCycle() {
        AdmissionCycleEntity cycle = cycle(2027, AdmissionCycleStatus.OPEN);
        when(repository.findByAcademicYearForUpdate(2027)).thenReturn(Optional.of(cycle));

        assertDoesNotThrow(() -> guard.assertOpenForCreate(2027));

        verify(jdbcTemplate).update(anyString(), eq(2027));
        verify(repository).findByAcademicYearForUpdate(2027);
    }

    @Test
    void rejectsAnyMutationOnceCycleIsPublishing() {
        AdmissionCycleEntity cycle = cycle(2027, AdmissionCycleStatus.PUBLISHING);
        ApplicationEntity application = new ApplicationEntity();
        application.setAcademicYear(2027);
        when(repository.findByAcademicYearForUpdate(2027)).thenReturn(Optional.of(cycle));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> guard.assertOpen(application));

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void rejectsMutationWhenExistingApplicationHasNoYear() {
        ApplicationEntity application = new ApplicationEntity();
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> guard.assertOpen(application));
        assertEquals(409, exception.getStatusCode().value());
    }

    private static AdmissionCycleEntity cycle(int year, AdmissionCycleStatus status) {
        AdmissionCycleEntity cycle = new AdmissionCycleEntity();
        cycle.setAcademicYear(year);
        cycle.setStatus(status);
        return cycle;
    }
}
