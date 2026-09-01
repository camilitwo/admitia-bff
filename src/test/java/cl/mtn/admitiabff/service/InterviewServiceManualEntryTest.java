package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.evaluation.EvaluationEntity;
import cl.mtn.admitiabff.domain.interview.InterviewEntity;
import cl.mtn.admitiabff.domain.interview.ManualInterviewCreateRequest;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.InterviewerScheduleRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InterviewServiceManualEntryTest {
    @Mock private InterviewRepository interviewRepository;
    @Mock private InterviewerScheduleRepository scheduleRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private UserRepository userRepository;
    @Mock private EvaluationRepository evaluationRepository;
    @Mock private EmailComposerService emailComposerService;
    @Mock private InterviewConfirmationService confirmationService;
    @Mock private InterviewerPairService interviewerPairService;

    private InterviewService service;
    private ApplicationEntity application;
    private UserEntity firstInterviewer;
    private UserEntity secondInterviewer;
    private UserEntity admin;

    @BeforeEach
    void setUp() {
        service = new InterviewService(
            interviewRepository,
            scheduleRepository,
            applicationRepository,
            userRepository,
            evaluationRepository,
            emailComposerService,
            confirmationService,
            interviewerPairService
        );

        StudentEntity student = new StudentEntity();
        student.setId(82L);
        student.setFirstName("Bautista José");
        student.setPaternalLastName("Brosel");
        student.setMaternalLastName("Johnson");
        student.setGradeApplied("6_BASICO");

        application = new ApplicationEntity();
        application.setId(120L);
        application.setStudent(student);
        application.setStatus(ApplicationStatus.PENDING);
        application.setArchived(false);

        firstInterviewer = interviewer(10L, "Anita", "Baeza");
        secondInterviewer = interviewer(11L, "Valentina", "Núñez");
        admin = interviewer(99L, "Admin", "MTN");
        admin.setRole(Role.ADMIN);

        when(applicationRepository.findActiveById(120L)).thenReturn(Optional.of(application));
        when(userRepository.findById(10L)).thenReturn(Optional.of(firstInterviewer));
        when(userRepository.findById(11L)).thenReturn(Optional.of(secondInterviewer));
        when(interviewRepository.findByApplicationIdOrderByScheduledDateDesc(120L)).thenReturn(List.of());
        when(scheduleRepository.findAvailableTemplates(any(), any(), any(), any())).thenReturn(List.of());
        when(interviewRepository.findBlockingForInterviewer(any(), any(), anyList())).thenReturn(List.of());
    }

    @Test
    void requiresConfirmationWhenManualEntryHasWarnings() {
        ManualInterviewCreateRequest request = request(false);

        ManualInterviewConfirmationException error = assertThrows(
            ManualInterviewConfirmationException.class,
            () -> service.createManual(request, 99L)
        );

        assertEquals("PAST_DATE", error.getWarnings().get(0).get("code"));
        verify(interviewRepository, never()).save(any());
        verifyNoInteractions(emailComposerService);
    }

    @Test
    void createsManualInterviewAndEvaluationWithoutEmailAfterConfirmation() {
        when(userRepository.findById(99L)).thenReturn(Optional.of(admin));
        when(interviewRepository.save(any(InterviewEntity.class))).thenAnswer(invocation -> {
            InterviewEntity interview = invocation.getArgument(0);
            interview.setId(901L);
            return interview;
        });
        when(evaluationRepository.findByApplicationIdAndEvaluationType(120L, "FAMILY_INTERVIEW"))
            .thenReturn(Optional.empty());
        when(evaluationRepository.save(any(EvaluationEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> response = service.createManual(request(true), 99L);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals("MANUAL", data.get("entrySource"));
        assertEquals(901L, data.get("id"));
        verify(evaluationRepository).save(any(EvaluationEntity.class));
        verifyNoInteractions(emailComposerService);
    }

    private ManualInterviewCreateRequest request(boolean confirmWarnings) {
        return new ManualInterviewCreateRequest(
            120L,
            10L,
            11L,
            LocalDate.of(2026, 8, 31),
            LocalTime.of(13, 0),
            40,
            "IN_PERSON",
            null,
            "Corrección de una entrevista realizada fuera de agenda",
            confirmWarnings
        );
    }

    private UserEntity interviewer(Long id, String firstName, String lastName) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setActive(true);
        user.setRole(Role.INTERVIEWER);
        return user;
    }
}
