package cl.mtn.admitiabff.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.service.InterviewConfirmationService;
import cl.mtn.admitiabff.service.NotificationService;
import cl.mtn.admitiabff.service.notification.template.EmailTemplateRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailComposerServiceDocumentReviewTest {
    @Mock EmailTemplateRegistry registry;
    @Mock NotificationService notificationService;
    @Mock InterviewConfirmationService confirmationService;

    @Test
    void derivesReviewStatusAndCommentsFromDocumentListsInNestedPayload() {
        EmailComposerService service = new EmailComposerService(
            registry, notificationService, confirmationService, "https://api.example.cl");
        when(notificationService.email(any())).thenReturn(Map.of("success", true));

        service.sendFromPayload(Map.of(
            "template", "DOCUMENT_REVIEW",
            "to", "familia@example.cl",
            "applicationId", 40L,
            "data", Map.of(
                "parentNames", "Familia González",
                "studentName", "Alonso González",
                "approvedDocuments", List.of("Certificado de nacimiento"),
                "rejectedDocuments", List.of("Informe de notas"),
                "allApproved", false)));

        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).email(sent.capture());
        String html = String.valueOf(sent.getValue().get("message"));

        assertThat(html).contains("postulación N°", "<strong>40</strong>");
        assertThat(html).contains("Estado de la revisión:</strong> Requiere correcciones");
        assertThat(html).contains("Documentos aprobados: Certificado de nacimiento.");
        assertThat(html).contains("Documentos que requieren corrección: Informe de notas.");
        assertThat(html).doesNotContain("Estado de la revisión:</strong> —");
        assertThat(html).doesNotContain("Comentarios:</strong> —");
    }

    @Test
    void preservesExplicitReviewStatusAndComments() {
        EmailComposerService service = new EmailComposerService(
            registry, notificationService, confirmationService, "https://api.example.cl");
        when(notificationService.email(any())).thenReturn(Map.of("success", true));

        service.sendFromPayload(Map.of(
            "template", "DOCUMENT_REVIEW",
            "to", "familia@example.cl",
            "reviewStatus", "Observada",
            "comments", "Falta una firma en el certificado."));

        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).email(sent.capture());
        String html = String.valueOf(sent.getValue().get("message"));
        assertThat(html).contains("Estado de la revisión:</strong> Observada");
        assertThat(html).contains("Comentarios:</strong> Falta una firma en el certificado.");
    }
}
