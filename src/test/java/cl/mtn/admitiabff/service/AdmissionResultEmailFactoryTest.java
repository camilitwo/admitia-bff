package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.person.GuardianEntity;
import cl.mtn.admitiabff.domain.person.ParentEntity;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.service.notification.template.EmailTemplateRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdmissionResultEmailFactoryTest {
    private AdmissionResultEmailFactory factory;
    private ApplicationEntity application;

    @BeforeEach
    void setUp() {
        EmailComposerService composer = new EmailComposerService(
                mock(EmailTemplateRegistry.class),
                mock(NotificationService.class),
                mock(InterviewConfirmationService.class),
                "http://localhost:8080");
        factory = new AdmissionResultEmailFactory(composer);

        StudentEntity student = new StudentEntity();
        student.setFirstName("ROBERTO");
        student.setPaternalLastName("GONZALEZ");
        student.setMaternalLastName("SAAVEDRA");
        student.setGradeApplied("5_BASICO");

        GuardianEntity guardian = new GuardianEntity();
        guardian.setFullName("CAMILO GONZALEZ");
        guardian.setEmail("APODERADO@EXAMPLE.CL");

        ParentEntity father = new ParentEntity();
        father.setFullName("CAMILO GONZALEZ");
        ParentEntity mother = new ParentEntity();
        mother.setFullName("MARÍA PÉREZ");

        application = new ApplicationEntity();
        application.setId(42L);
        application.setStudent(student);
        application.setGuardian(guardian);
        application.setFather(father);
        application.setMother(mother);
        application.setStatus(ApplicationStatus.WAITLIST);
    }

    @Test
    void preparesSpanishWaitlistEmailForFormalGuardianAndIncludesMother() {
        AdmissionResultEmailFactory.PreparedEmail result = factory.prepare(application);

        assertEquals("apoderado@example.cl", result.recipient());
        assertEquals("Camilo Gonzalez", result.recipientName());
        assertEquals("Resultado de admisión: lista de espera", result.subject());
        assertTrue(result.html().contains("Estimado/a <strong>Camilo Gonzalez</strong>"));
        assertTrue(result.html().contains("María Pérez"));
        assertTrue(result.html().contains("Lista de espera"));
        assertFalse(result.html().contains("WAITLIST"));
        assertFalse(result.html().contains("Estado anterior"));
        assertFalse(result.html().contains("Estado actual"));
    }
}
