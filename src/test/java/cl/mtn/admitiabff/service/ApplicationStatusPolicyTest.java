package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class ApplicationStatusPolicyTest {
    static Stream<Arguments> transitions() {
        return Stream.of(ApplicationStatus.APPROVED, ApplicationStatus.REJECTED, ApplicationStatus.WAITLIST)
            .flatMap(from -> Stream.of(ApplicationStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest
    @MethodSource("transitions")
    void protectsEveryFinalStateAgainstEveryDestination(ApplicationStatus from, ApplicationStatus to) {
        var app = application(from, "KINDER");
        boolean permitted = from == to || from == ApplicationStatus.WAITLIST
            && (to == ApplicationStatus.APPROVED || to == ApplicationStatus.REJECTED);
        if (permitted) assertDoesNotThrow(() -> ApplicationStatusPolicy.validate(app, to));
        else assertThrows(ApplicationStatusPolicy.StatusTransitionException.class, () -> ApplicationStatusPolicy.validate(app, to));
        assertEquals(from, app.getStatus());
    }

    @Test
    void excludesAllLegacyPrekinderSpellingsAndKeepsNonFinalTransitions() {
        for (String grade : new String[]{"PRE_KINDER", "PREKINDER", "Prekínder", "Pre-kínder", "PK"}) {
            assertDoesNotThrow(() -> ApplicationStatusPolicy.validate(application(ApplicationStatus.APPROVED, grade), ApplicationStatus.PENDING));
        }
        for (ApplicationStatus to : ApplicationStatus.values()) {
            assertDoesNotThrow(() -> ApplicationStatusPolicy.validate(application(ApplicationStatus.UNDER_REVIEW, "IV Medio"), to));
        }
    }

    @Test
    void requiresAnAuthorizedHumanAndAuditsOnlyActualChanges() {
        var auth = mock(AuthService.class);
        var jdbc = mock(JdbcTemplate.class);
        var actor = new AuthService.AuthContextHolder(7L, "admin@example.cl", "ADMIN");
        when(auth.requireAuth()).thenReturn(actor);
        var service = new ApplicationStatusTransitionService(auth, jdbc);
        var app = application(ApplicationStatus.WAITLIST, "IV_MEDIO");
        assertThrows(ResponseStatusException.class, () -> service.transition(app, ApplicationStatus.APPROVED));
        verifyNoInteractions(jdbc);
        assertEquals(ApplicationStatus.WAITLIST, app.getStatus());
        when(auth.hasAnyRoleContext(actor, Role.ADMIN, Role.COORDINATOR)).thenReturn(true);
        assertTrue(service.transition(app, ApplicationStatus.APPROVED));
        verify(jdbc).update(contains("INSERT INTO application_status_history"), eq(1L), eq("WAITLIST"), eq("APPROVED"), eq(7L));
        assertFalse(service.transition(app, ApplicationStatus.APPROVED));
        assertThrows(ApplicationStatusPolicy.StatusTransitionException.class, () -> service.transition(app, ApplicationStatus.REJECTED));
        verifyNoMoreInteractions(jdbc);
    }

    static ApplicationEntity application(ApplicationStatus status, String grade) {
        var app = new ApplicationEntity();
        app.setId(1L);
        app.setStatus(status);
        var student = new StudentEntity();
        student.setGradeApplied(grade);
        app.setStudent(student);
        return app;
    }
}
