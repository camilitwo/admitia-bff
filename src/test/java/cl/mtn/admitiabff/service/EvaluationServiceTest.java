package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.evaluation.EvaluationEntity;
import cl.mtn.admitiabff.domain.interview.InterviewEntity;
import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.util.JsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

class EvaluationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void updatePersistsAndReturnsCommonAndStructuredEvaluationFields() {
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        EvaluationEntity evaluation = new EvaluationEntity();
        evaluation.setId(41L);
        evaluation.setEvaluationType("MATHEMATICS_EXAM");
        evaluation.setStatus(EvaluationStatus.PENDING);
        when(evaluationRepository.findById(41L)).thenReturn(Optional.of(evaluation));
        when(evaluationRepository.save(evaluation)).thenReturn(evaluation);

        EvaluationService service = service(evaluationRepository);
        Map<String, Object> result = service.update(41L, Map.of(
            "grade", "A",
            "strengths", "Perseverancia y concentración",
            "areasForImprovement", "Manejo del tiempo",
            "interviewData", Map.of(
                "formType", "ADMISSION_REPORT",
                "difficulties", "Ansiedad inicial",
                "examAdaptation", "Comprende instrucciones"
            ),
            "status", "COMPLETED"
        ));

        assertEquals("A", evaluation.getGrade());
        assertEquals("Perseverancia y concentración", evaluation.getStrengths());
        assertEquals("Manejo del tiempo", evaluation.getAreasForImprovement());
        assertTrue(evaluation.getInterviewData().contains("Ansiedad inicial"));
        assertEquals(EvaluationStatus.COMPLETED, evaluation.getStatus());
        assertTrue(evaluation.getCompletedAt() != null);

        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("Perseverancia y concentración", data.get("strengths"));
        assertEquals("Ansiedad inicial", data.get("difficulties"));
        assertEquals("Comprende instrucciones", data.get("examAdaptation"));
    }

    @Test
    void familyInterviewPersistsNestedResponsesAndCalculatesTheirScore() {
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        EvaluationEntity evaluation = new EvaluationEntity();
        evaluation.setId(52L);
        evaluation.setEvaluationType("FAMILY_INTERVIEW");
        evaluation.setStatus(EvaluationStatus.IN_PROGRESS);
        when(evaluationRepository.findById(52L)).thenReturn(Optional.of(evaluation));

        EvaluationService service = service(evaluationRepository);
        Map<String, Object> answers = Map.of(
            "section1", Map.of(
                "question1", Map.of("score", 3, "text", "Respuesta uno"),
                "question2", Map.of("score", 4, "text", "Respuesta dos")
            ),
            "observations", Map.of(
                "checklist", Map.of("collaborative", true, "punctual", false),
                "overallOpinion", Map.of("score", 5)
            )
        );

        service.saveFamilyInterviewData(52L, Map.of("interviewData", answers));

        assertEquals(new BigDecimal("13.0"), evaluation.getFamilyInterviewScore());
        assertEquals(EvaluationStatus.COMPLETED, evaluation.getStatus());
        assertTrue(evaluation.getInterviewData().contains("Respuesta uno"));
        verify(evaluationRepository).save(evaluation);
    }

    @Test
    @SuppressWarnings("unchecked")
    void psychologistOnlyReceivesPsychologicalEvaluationFromCycleInterview() {
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        InterviewRepository interviewRepository = mock(InterviewRepository.class);
        AuthService authService = mock(AuthService.class);
        when(authService.requireAuth()).thenReturn(new AuthService.AuthContextHolder(22L, "psicologa@mtn.cl", "PSYCHOLOGIST"));

        UserEntity director = user(11L);
        UserEntity psychologist = user(22L);
        ApplicationEntity application = new ApplicationEntity();
        application.setId(70L);
        InterviewEntity interview = new InterviewEntity();
        interview.setId(80L);
        interview.setApplication(application);
        interview.setInterviewType("CYCLE_DIRECTOR");
        interview.setInterviewer(director);
        interview.setSecondInterviewer(psychologist);
        interview.setStatus(InterviewStatus.SCHEDULED);

        EvaluationEntity psychological = new EvaluationEntity();
        psychological.setId(90L);
        psychological.setApplication(application);
        psychological.setEvaluator(psychologist);
        psychological.setEvaluationType("PSYCHOLOGICAL_INTERVIEW");
        psychological.setStatus(EvaluationStatus.PENDING);

        when(evaluationRepository.findByEvaluatorIdOrderByCreatedAtDesc(22L)).thenReturn(List.of(psychological));
        when(interviewRepository.findVisibleForInterviewer(any(), any())).thenReturn(List.of(interview));

        EvaluationService service = service(evaluationRepository, interviewRepository, authService);
        Map<String, Object> result = service.myEvaluations();
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");

        assertEquals(1, data.size());
        assertEquals("PSYCHOLOGICAL_INTERVIEW", data.getFirst().get("type"));
    }

    @Test
    void evaluatorCannotUpdateAnotherProfessionalsEvaluation() {
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        InterviewRepository interviewRepository = mock(InterviewRepository.class);
        AuthService authService = mock(AuthService.class);
        when(authService.requireAuth()).thenReturn(new AuthService.AuthContextHolder(22L, "psicologa@mtn.cl", "PSYCHOLOGIST"));

        ApplicationEntity application = new ApplicationEntity();
        application.setId(70L);
        EvaluationEntity evaluation = new EvaluationEntity();
        evaluation.setId(91L);
        evaluation.setApplication(application);
        evaluation.setEvaluator(user(11L));
        evaluation.setEvaluationType("CYCLE_DIRECTOR_REPORT");
        evaluation.setStatus(EvaluationStatus.PENDING);
        when(evaluationRepository.findById(91L)).thenReturn(Optional.of(evaluation));
        when(interviewRepository.findByApplicationIdOrderByScheduledDateDesc(70L)).thenReturn(List.of());

        EvaluationService service = service(evaluationRepository, interviewRepository, authService);

        assertThrows(AccessDeniedException.class,
            () -> service.update(91L, Map.of("strengths", "No debe guardarse")));
    }

    @Test
    void evaluatorCannotChangeAssignmentFieldsOnOwnEvaluation() {
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        InterviewRepository interviewRepository = mock(InterviewRepository.class);
        AuthService authService = mock(AuthService.class);
        when(authService.requireAuth()).thenReturn(new AuthService.AuthContextHolder(22L, "psicologa@mtn.cl", "PSYCHOLOGIST"));

        EvaluationEntity evaluation = new EvaluationEntity();
        evaluation.setId(92L);
        evaluation.setEvaluator(user(22L));
        evaluation.setEvaluationType("PSYCHOLOGICAL_INTERVIEW");
        evaluation.setStatus(EvaluationStatus.PENDING);
        when(evaluationRepository.findById(92L)).thenReturn(Optional.of(evaluation));

        EvaluationService service = service(evaluationRepository, interviewRepository, authService);

        assertThrows(AccessDeniedException.class,
            () -> service.update(92L, Map.of("evaluatorId", 11L, "strengths", "Intento inválido")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void psychologicalEnsureReturnsSecondInterviewerAsResponsibleEvaluator() {
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        InterviewRepository interviewRepository = mock(InterviewRepository.class);
        AuthService authService = mock(AuthService.class);
        when(authService.requireAuth()).thenReturn(new AuthService.AuthContextHolder(22L, "psicologa@mtn.cl", "PSYCHOLOGIST"));

        UserEntity director = user(11L);
        UserEntity psychologist = user(22L);
        ApplicationEntity application = new ApplicationEntity();
        application.setId(70L);
        InterviewEntity interview = new InterviewEntity();
        interview.setId(80L);
        interview.setApplication(application);
        interview.setInterviewType("CYCLE_DIRECTOR");
        interview.setInterviewer(director);
        interview.setSecondInterviewer(psychologist);
        interview.setStatus(InterviewStatus.SCHEDULED);

        EvaluationEntity psychological = new EvaluationEntity();
        psychological.setId(93L);
        psychological.setApplication(application);
        psychological.setEvaluator(psychologist);
        psychological.setEvaluationType("PSYCHOLOGICAL_INTERVIEW");
        psychological.setStatus(EvaluationStatus.PENDING);
        when(interviewRepository.findById(80L)).thenReturn(Optional.of(interview));
        when(evaluationRepository.findByApplicationIdAndEvaluationType(70L, "PSYCHOLOGICAL_INTERVIEW"))
            .thenReturn(Optional.of(psychological));

        EvaluationService service = service(evaluationRepository, interviewRepository, authService);
        Map<String, Object> result = service.ensureInterviewEvaluations(80L);
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        Map<String, Object> evaluator = (Map<String, Object>) data.getFirst().get("evaluator");

        assertEquals(1, data.size());
        assertEquals("PSYCHOLOGICAL_INTERVIEW", data.getFirst().get("type"));
        assertEquals(22L, data.getFirst().get("evaluatorId"));
        assertEquals(22L, evaluator.get("id"));
    }

    private EvaluationService service(EvaluationRepository evaluationRepository) {
        AuthService authService = mock(AuthService.class);
        AuthService.AuthContextHolder admin = new AuthService.AuthContextHolder(1L, "admin@mtn.cl", "ADMIN");
        when(authService.requireAuth()).thenReturn(admin);
        when(authService.isAdminContext(admin)).thenReturn(true);
        return service(evaluationRepository, mock(InterviewRepository.class), authService);
    }

    private EvaluationService service(
        EvaluationRepository evaluationRepository,
        InterviewRepository interviewRepository,
        AuthService authService
    ) {
        return new EvaluationService(
            evaluationRepository,
            mock(ApplicationRepository.class),
            interviewRepository,
            mock(UserRepository.class),
            mock(EmailComposerService.class),
            authService,
            new JsonSupport(new ObjectMapper())
        );
    }

    private UserEntity user(Long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        return user;
    }

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
                mock(JsonSupport.class));

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
