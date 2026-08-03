package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.common.Role;
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
import cl.mtn.admitiabff.util.JsonSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApplicationServiceFinalDecisionTest {

    private ApplicationRepository applicationRepository;
    private ApplicationService service;
    private ApplicationEntity application;
    private AdmissionCycleGuard admissionCycleGuard;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        admissionCycleGuard = mock(AdmissionCycleGuard.class);
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
                admissionCycleGuard,
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
    void waitlistPersistsDecisionAndDefersEmailUntilCycleClose() {
        Map<String, Object> response = service.recordFinalDecision(
                30L,
                Map.of("decision", "WAITLIST", "note", "Les contactaremos al liberarse un cupo."));

        assertEquals(ApplicationStatus.WAITLIST, application.getStatus());
        assertEquals("Postulación agregada a la lista de espera", response.get("message"));

        Map<?, ?> notification = (Map<?, ?>) response.get("notification");
        assertFalse((Boolean) notification.get("attempted"));
        assertFalse((Boolean) notification.get("sent"));
        assertEquals("DEFERRED_UNTIL_PROCESS_CLOSE", notification.get("status"));
        verify(admissionCycleGuard).assertOpen(application);
    }

    @Test
    void approvedDecisionIsPersistedWithoutCallingEmailProvider() {
        Map<String, Object> response = service.recordFinalDecision(
                30L,
                Map.of("decision", "APPROVED"));

        assertEquals(ApplicationStatus.APPROVED, application.getStatus());
        Map<?, ?> notification = (Map<?, ?>) response.get("notification");
        assertFalse((Boolean) notification.get("attempted"));
        assertFalse((Boolean) notification.get("sent"));
        assertEquals("DEFERRED_UNTIL_PROCESS_CLOSE", notification.get("status"));
    }

    @Test
    void repeatedApprovalRemainsDeferredAndDoesNotDuplicateEmail() {
        application.setStatus(ApplicationStatus.APPROVED);
        Map<String, Object> response = service.recordFinalDecision(30L, Map.of("decision", "APPROVED"));
        Map<?, ?> notification = (Map<?, ?>) response.get("notification");
        assertEquals("DEFERRED_UNTIL_PROCESS_CLOSE", notification.get("status"));
    }
}
