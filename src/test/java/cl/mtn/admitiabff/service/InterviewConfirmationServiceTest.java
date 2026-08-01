package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.interview.InterviewEntity;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InterviewConfirmationServiceTest {

    @Test
    void confirmationEmailUsesFriendlyInterviewTypeAndMode() {
        InterviewRepository repository = mock(InterviewRepository.class);
        EmailComposerService composer = mock(EmailComposerService.class);
        InterviewConfirmationService service = new InterviewConfirmationService(
                repository,
                composer,
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "https://admisiones.cmtn.cl",
                "/interview/confirmation-result",
                168,
                "admision@mtn.cl");

        StudentEntity student = new StudentEntity();
        student.setFirstName("ALONSO");
        student.setPaternalLastName("GONZALEZ");
        ApplicationEntity application = new ApplicationEntity();
        application.setStudent(student);

        UserEntity interviewer = new UserEntity();
        interviewer.setFirstName("Entrevistador");
        interviewer.setLastName("Uno");
        interviewer.setEmail("entrevistador@mtn.cl");

        InterviewEntity interview = new InterviewEntity();
        interview.setId(42L);
        interview.setApplication(application);
        interview.setInterviewer(interviewer);
        interview.setInterviewType("FAMILY");
        interview.setScheduledDate(LocalDate.of(2026, 8, 6));
        interview.setScheduledTime(LocalTime.of(12, 0));
        interview.setMode("IN_PERSON");
        interview.setLocation("Sala 2");
        interview.setStatus(InterviewStatus.SCHEDULED);

        when(repository.findById(42L)).thenReturn(Optional.of(interview));
        when(repository.save(interview)).thenReturn(interview);
        when(composer.send(any(EmailRequestDTO.class))).thenReturn(Map.of("success", true));

        String confirmationUrl = service.generateConfirmationUrl("https://api.cmtn.cl", 42L, true);
        String token = confirmationUrl.substring(confirmationUrl.indexOf("token=") + "token=".length());
        service.processConfirmationAndGetRedirectUrl(token);

        ArgumentCaptor<EmailRequestDTO> requests = ArgumentCaptor.forClass(EmailRequestDTO.class);
        verify(composer, times(2)).send(requests.capture());
        List<EmailRequestDTO> sent = requests.getAllValues();
        EmailRequestDTO interviewerEmail = sent.stream()
                .filter(request -> "INTERVIEWER".equals(request.recipientType))
                .findFirst()
                .orElseThrow();

        assertEquals("Familia", interviewerEmail.data.get("interviewType"));
        assertEquals("Presencial", interviewerEmail.data.get("mode"));
        assertTrue(interviewerEmail.template.contains("Tipo de entrevista:</strong> Familia"));
        assertTrue(interviewerEmail.template.contains("Modalidad:</strong> Presencial"));
        assertFalse(interviewerEmail.template.contains("FAMILY"));
        assertFalse(interviewerEmail.template.contains("IN_PERSON"));
        assertEquals(InterviewStatus.CONFIRMED, interview.getStatus());
        verify(repository).save(interview);
    }
}
