package cl.mtn.admitiabff.controller;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.config.AuthContext;
import cl.mtn.admitiabff.config.AuthUser;
import cl.mtn.admitiabff.domain.interview.ManualInterviewCreateRequest;
import cl.mtn.admitiabff.service.InterviewService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

@ExtendWith(MockitoExtension.class)
class InterviewsControllerTest {

    @Mock
    private InterviewService interviewService;

    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    @Test
    void sendInvitationUsesConfiguredPublicBaseUrl() {
        String publicBaseUrl = "https://admitia-nginx-staging.up.railway.app";
        Map<String, Object> expected = Map.of("success", true);
        when(interviewService.sendInterviewInvitation(84L, publicBaseUrl)).thenReturn(expected);

        InterviewsController controller = new InterviewsController(interviewService, publicBaseUrl);

        assertSame(expected, controller.sendInvitation(84L));
        verify(interviewService).sendInterviewInvitation(84L, publicBaseUrl);
    }

    @Test
    void manualEntryIsRestrictedToAdministrators() throws NoSuchMethodException {
        PreAuthorize annotation = InterviewsController.class
            .getMethod("createManual", cl.mtn.admitiabff.domain.interview.ManualInterviewCreateRequest.class)
            .getAnnotation(PreAuthorize.class);

        assertEquals("hasRole('ADMIN')", annotation.value());
    }

    @Test
    void manualEntryDoesNotSendEmailByDefault() {
        String publicBaseUrl = "https://admitia.example";
        ManualInterviewCreateRequest request = manualRequest(false);
        when(interviewService.createManual(request, 99L)).thenReturn(Map.of(
            "success", true,
            "data", Map.of("id", 321L, "applicationId", 120L)
        ));
        AuthContext.set(new AuthUser(99L, "admin@mtn.cl", "ADMIN"));

        Map<String, Object> response = new InterviewsController(interviewService, publicBaseUrl)
            .createManual(request);

        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals(false, data.get("emailRequested"));
        assertEquals(false, data.get("emailSent"));
        verify(interviewService, never()).sendInterviewInvitation(321L, publicBaseUrl);
    }

    @Test
    void manualEntrySendsInvitationOnlyWhenExplicitlyRequested() {
        String publicBaseUrl = "https://admitia.example";
        ManualInterviewCreateRequest request = manualRequest(true);
        when(interviewService.createManual(request, 99L)).thenReturn(Map.of(
            "success", true,
            "data", Map.of("id", 322L, "applicationId", 120L)
        ));
        when(interviewService.sendInterviewInvitation(322L, publicBaseUrl)).thenReturn(Map.of(
            "success", true,
            "message", "Invitación enviada con confirmación"
        ));
        AuthContext.set(new AuthUser(99L, "admin@mtn.cl", "ADMIN"));

        Map<String, Object> response = new InterviewsController(interviewService, publicBaseUrl)
            .createManual(request);

        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals(true, data.get("emailRequested"));
        assertEquals(true, data.get("emailSent"));
        verify(interviewService).sendInterviewInvitation(322L, publicBaseUrl);
    }

    @Test
    void manualEntryRemainsSuccessfulWhenRequestedEmailFails() {
        String publicBaseUrl = "https://admitia.example";
        ManualInterviewCreateRequest request = manualRequest(true);
        when(interviewService.createManual(request, 99L)).thenReturn(Map.of(
            "success", true,
            "data", Map.of("id", 323L, "applicationId", 120L)
        ));
        when(interviewService.sendInterviewInvitation(323L, publicBaseUrl))
            .thenThrow(new IllegalStateException("Sin email de apoderado"));
        AuthContext.set(new AuthUser(99L, "admin@mtn.cl", "ADMIN"));

        Map<String, Object> response = new InterviewsController(interviewService, publicBaseUrl)
            .createManual(request);

        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals(true, response.get("success"));
        assertEquals(true, data.get("emailRequested"));
        assertEquals(false, data.get("emailSent"));
        assertEquals("La entrevista quedó guardada, pero no se pudieron enviar los correos.", data.get("emailMessage"));
    }

    private ManualInterviewCreateRequest manualRequest(boolean sendEmail) {
        return new ManualInterviewCreateRequest(
            120L,
            "FAMILY",
            10L,
            11L,
            LocalDate.of(2026, 9, 1),
            LocalTime.of(10, 0),
            60,
            "IN_PERSON",
            "Sala 1",
            "Ingreso excepcional validado por administración",
            sendEmail,
            false
        );
    }
}
