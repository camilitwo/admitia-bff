package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.common.PaymentStatus;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.person.ParentEntity;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.ComplementaryFormRepository;
import cl.mtn.admitiabff.repository.DocumentRepository;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.GuardianRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.ParentRepository;
import cl.mtn.admitiabff.repository.StudentRepository;
import cl.mtn.admitiabff.repository.SupporterRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.util.JsonSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class ApplicationServiceFinalDecisionTest {

    private ApplicationRepository applicationRepository;
    private EmailComposerService emailComposerService;
    private ApplicationService service;
    private ApplicationEntity application;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        emailComposerService = mock(EmailComposerService.class);
        AuthService authService = mock(AuthService.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        ComplementaryFormRepository complementaryFormRepository = mock(ComplementaryFormRepository.class);
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        InterviewRepository interviewRepository = mock(InterviewRepository.class);

        service = new ApplicationService(
                applicationRepository,
                mock(StudentRepository.class),
                mock(ParentRepository.class),
                mock(GuardianRepository.class),
                mock(SupporterRepository.class),
                mock(UserRepository.class),
                documentRepository,
                complementaryFormRepository,
                evaluationRepository,
                interviewRepository,
                authService,
                mock(NotificationService.class),
                emailComposerService,
                mock(JsonSupport.class),
                "uploads");

        StudentEntity student = new StudentEntity();
        student.setId(10L);
        student.setFirstName("Roberto");
        student.setPaternalLastName("González");
        student.setGradeApplied("5_BASICO");

        UserEntity applicant = new UserEntity();
        applicant.setId(20L);
        applicant.setFirstName("MARÍA");
        applicant.setLastName("PÉREZ");
        applicant.setEmail("familia@example.cl");

        ParentEntity father = new ParentEntity();
        father.setId(21L);
        father.setFullName("CAMILO GONZÁLEZ");
        father.setRut("");
        father.setEmail("");
        father.setPhone("");
        father.setAddress("");
        father.setProfession("");
        ParentEntity mother = new ParentEntity();
        mother.setId(22L);
        mother.setFullName("A");
        mother.setRut("");
        mother.setEmail("");
        mother.setPhone("");
        mother.setAddress("");
        mother.setProfession("");

        application = new ApplicationEntity();
        application.setId(30L);
        application.setStudent(student);
        application.setApplicantUser(applicant);
        application.setFather(father);
        application.setMother(mother);
        application.setStatus(ApplicationStatus.UNDER_REVIEW);
        application.setSubmissionDate(LocalDateTime.now());

        AuthService.AuthContextHolder auth = new AuthService.AuthContextHolder(1L, "admin@mtn.cl", "ADMIN");
        when(authService.requireAuth()).thenReturn(auth);
        when(authService.hasAnyRoleContext(auth, Role.ADMIN, Role.COORDINATOR)).thenReturn(true);
        when(applicationRepository.findActiveById(30L)).thenReturn(Optional.of(application));
        when(applicationRepository.save(application)).thenReturn(application);
        when(documentRepository.findByApplicationIdOrderByUploadDateDesc(30L)).thenReturn(List.of());
        when(complementaryFormRepository.existsByApplicationIdAndSubmittedTrue(30L)).thenReturn(false);
        when(evaluationRepository.findByApplicationIdOrderByCreatedAtDesc(30L)).thenReturn(List.of());
        when(interviewRepository.findByApplicationIdOrderByScheduledDateDesc(30L)).thenReturn(List.of());
    }

    @Test
    void waitlistPersistsDecisionSendsEmailAndReportsConfirmedDelivery() {
        when(emailComposerService.send(any(EmailRequestDTO.class))).thenReturn(Map.of(
                "success", true,
                "data", Map.of("status", "SENT", "recipient", "familia@example.cl")));

        Map<String, Object> response = service.recordFinalDecision(
                30L,
                Map.of("decision", "WAITLIST", "note", "Les contactaremos al liberarse un cupo."));

        assertEquals(ApplicationStatus.WAITLIST, application.getStatus());
        assertEquals("Postulación agregada a la lista de espera", response.get("message"));

        Map<?, ?> notification = (Map<?, ?>) response.get("notification");
        assertTrue((Boolean) notification.get("attempted"));
        assertTrue((Boolean) notification.get("sent"));
        assertEquals("SENT", notification.get("status"));
        assertEquals("familia@example.cl", notification.get("recipient"));

        ArgumentCaptor<EmailRequestDTO> request = ArgumentCaptor.forClass(EmailRequestDTO.class);
        verify(emailComposerService).send(request.capture());
        assertEquals("familia@example.cl", request.getValue().to);
        assertEquals("En revisión", request.getValue().data.get("previousStatus"));
        assertEquals("Lista de espera", request.getValue().data.get("currentStatus"));
        assertEquals("Camilo González y María Pérez", request.getValue().data.get("familyNames"));
        assertEquals("Lista de espera", request.getValue().data.get("result"));
        assertEquals("Resultado de admisión: lista de espera", request.getValue().subject);
        assertEquals("ADMISSION_RESULT", request.getValue().templateName);
        assertTrue(request.getValue().template.contains("Estimada familia <strong>Camilo González y María Pérez</strong>"));
        assertTrue(request.getValue().template.contains("Decisión final"));
        assertFalse(request.getValue().template.contains("WAITLIST"));
        assertFalse(request.getValue().template.contains("UNDER_REVIEW"));
    }

    @Test
    void emailFailureDoesNotUndoDecisionAndIsReportedToTheFrontend() {
        when(emailComposerService.send(any(EmailRequestDTO.class)))
                .thenThrow(new RuntimeException("provider unavailable"));

        Map<String, Object> response = service.recordFinalDecision(
                30L,
                Map.of("decision", "APPROVED"));

        assertEquals(ApplicationStatus.APPROVED, application.getStatus());
        Map<?, ?> notification = (Map<?, ?>) response.get("notification");
        assertTrue((Boolean) notification.get("attempted"));
        assertFalse((Boolean) notification.get("sent"));
        assertEquals("FAILED", notification.get("status"));
    }

    @Test
    void repeatedApprovalEmailShowsOneSpanishDecisionWithoutRedundantTransition() {
        application.setStatus(ApplicationStatus.APPROVED);
        when(emailComposerService.send(any(EmailRequestDTO.class))).thenReturn(Map.of(
                "success", true,
                "data", Map.of("status", "SENT")));

        service.recordFinalDecision(30L, Map.of("decision", "APPROVED"));

        ArgumentCaptor<EmailRequestDTO> request = ArgumentCaptor.forClass(EmailRequestDTO.class);
        verify(emailComposerService).send(request.capture());
        String template = request.getValue().template;
        assertTrue(template.contains("Aprobada"));
        assertFalse(template.contains("APPROVED"));
        assertFalse(template.contains("Estado anterior"));
        assertFalse(template.contains("Estado actual"));
    }

    @Test
    void adminListIncludesAdmissionPaymentStatus() {
        LocalDateTime paidAt = LocalDateTime.of(2026, 8, 3, 14, 30);
        application.setPaymentStatus(PaymentStatus.PAID);
        application.setPaymentRequired(true);
        application.setPaidAt(paidAt);
        when(applicationRepository.search(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(application)));

        Map<String, Object> response = service.list(0, 15, null, null, null);

        List<?> data = (List<?>) response.get("data");
        Map<?, ?> row = (Map<?, ?>) data.getFirst();
        assertEquals("PAID", row.get("paymentStatus"));
        assertEquals(true, row.get("paymentRequired"));
        assertEquals(paidAt, row.get("paidAt"));
    }
}
