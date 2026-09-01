package cl.mtn.admitiabff.controller;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.service.InterviewService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

@ExtendWith(MockitoExtension.class)
class InterviewsControllerTest {

    @Mock
    private InterviewService interviewService;

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
}
