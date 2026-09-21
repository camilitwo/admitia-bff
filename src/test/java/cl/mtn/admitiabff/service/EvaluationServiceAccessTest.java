package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.util.JsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Los profesionales leen las evaluaciones de una postulación; el listado global sigue siendo de administración. */
class EvaluationServiceAccessTest {

    private static final AuthService.AuthContextHolder PSYCHOLOGIST =
        new AuthService.AuthContextHolder(95L, "amaya.cerda@mtn.cl", "PSYCHOLOGIST");
    private static final AuthService.AuthContextHolder GUARDIAN =
        new AuthService.AuthContextHolder(173L, "apoderado@example.cl", "APODERADO");
    private static final AuthService.AuthContextHolder ADMIN =
        new AuthService.AuthContextHolder(1L, "admin@mtn.cl", "ADMIN");

    @Test
    void psychologistReadsTheEvaluationsOfAnApplication() {
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        AuthService authService = mock(AuthService.class);
        when(authService.requireAuth()).thenReturn(PSYCHOLOGIST);
        when(evaluationRepository.findByApplicationIdOrderByCreatedAtDesc(96L)).thenReturn(List.of());

        Map<String, Object> response = service(evaluationRepository, authService).byApplication(96L);

        assertEquals(true, response.get("success"));
        assertEquals(0, response.get("count"));
        verify(evaluationRepository).findByApplicationIdOrderByCreatedAtDesc(96L);
    }

    @Test
    void guardianCannotReadTheEvaluationsOfAnApplication() {
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        AuthService authService = mock(AuthService.class);
        when(authService.requireAuth()).thenReturn(GUARDIAN);

        assertThrows(ResponseStatusException.class,
            () -> service(evaluationRepository, authService).byApplication(96L));

        verify(evaluationRepository, never()).findByApplicationIdOrderByCreatedAtDesc(anyLong());
    }

    @Test
    void onlyAdministrationListsEveryEvaluation() {
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        AuthService authService = mock(AuthService.class);
        when(authService.requireAuth()).thenReturn(ADMIN);
        when(authService.isAdminContext(ADMIN)).thenReturn(true);
        when(evaluationRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        Map<String, Object> response = service(evaluationRepository, authService).all();

        assertEquals(true, response.get("success"));
    }

    @Test
    void professionalsCannotListEveryEvaluation() {
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        AuthService authService = mock(AuthService.class);
        when(authService.requireAuth()).thenReturn(PSYCHOLOGIST);
        when(authService.isAdminContext(PSYCHOLOGIST)).thenReturn(false);

        assertThrows(ResponseStatusException.class, () -> service(evaluationRepository, authService).all());

        verify(evaluationRepository, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void readerRolesAreExplicitAndFailClosed() {
        assertTrue(EvaluationService.canReadApplicationEvaluations("PSYCHOLOGIST"));
        assertTrue(EvaluationService.canReadApplicationEvaluations("cycle_director"));
        assertTrue(EvaluationService.canReadApplicationEvaluations("PREKINDER_PROFESSIONAL"));
        assertFalse(EvaluationService.canReadApplicationEvaluations("APODERADO"));
        assertFalse(EvaluationService.canReadApplicationEvaluations(null));
        assertFalse(EvaluationService.canReadApplicationEvaluations("VISITANTE"));
    }

    private EvaluationService service(EvaluationRepository evaluationRepository, AuthService authService) {
        return new EvaluationService(
            evaluationRepository,
            mock(ApplicationRepository.class),
            mock(InterviewRepository.class),
            mock(UserRepository.class),
            mock(EmailComposerService.class),
            authService,
            new JsonSupport(new ObjectMapper()));
    }
}
