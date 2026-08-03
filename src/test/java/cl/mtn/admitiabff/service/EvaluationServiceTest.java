package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.evaluation.EvaluationEntity;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.util.JsonSupport;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EvaluationServiceTest {

    @Test
    void assignmentEmailFormatsGradeAndOmitsEvaluationType() {
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        ApplicationRepository applicationRepository = mock(ApplicationRepository.class);
        InterviewRepository interviewRepository = mock(InterviewRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        EmailComposerService composer = mock(EmailComposerService.class);
        EvaluationService service = new EvaluationService(
                evaluationRepository,
                applicationRepository,
                interviewRepository,
                userRepository,
                composer,
                mock(AuthService.class),
                mock(JsonSupport.class),
                mock(AdmissionCycleGuard.class));

        StudentEntity student = new StudentEntity();
        student.setFirstName("ALONSO");
        student.setPaternalLastName("GONZALEZ");
        student.setGradeApplied("1_MEDIO");
        ApplicationEntity application = new ApplicationEntity();
        application.setStudent(student);

        EvaluationEntity evaluation = new EvaluationEntity();
        evaluation.setId(8L);
        evaluation.setApplication(application);
        evaluation.setSubject("MATHEMATICS");
        evaluation.setStatus(EvaluationStatus.PENDING);

        UserEntity evaluator = new UserEntity();
        evaluator.setId(3L);
        evaluator.setFirstName("Jorge");
        evaluator.setLastName("Profesor");
        evaluator.setEmail("jorge@mtn.cl");
        evaluator.setSubject("MATHEMATICS");

        LocalDateTime deadline = LocalDateTime.of(2026, 8, 1, 0, 0);
        when(evaluationRepository.findById(8L)).thenReturn(Optional.of(evaluation));
        when(userRepository.findById(3L)).thenReturn(Optional.of(evaluator));
        when(evaluationRepository.save(evaluation)).thenReturn(evaluation);
        when(composer.send(any(EmailRequestDTO.class))).thenReturn(Map.of("success", true));

        service.assign(8L, Map.of("evaluatorId", 3L, "evaluationDate", deadline));

        ArgumentCaptor<EmailRequestDTO> request = ArgumentCaptor.forClass(EmailRequestDTO.class);
        verify(composer).send(request.capture());
        EmailRequestDTO email = request.getValue();

        assertEquals("1 Medio", email.data.get("gradeApplied"));
        assertFalse(email.data.containsKey("evaluationType"));
        assertTrue(email.template.contains("Curso al que postula:</strong> 1 Medio"));
        assertFalse(email.template.contains("Tipo de evaluación:"));
        assertFalse(email.template.contains("{{evaluationType}}"));
    }
}
