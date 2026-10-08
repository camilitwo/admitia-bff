package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.evaluation.EvaluationEntity;
import cl.mtn.admitiabff.domain.interview.InterviewEntity;
import cl.mtn.admitiabff.domain.interview.InterviewerScheduleEntity;
import cl.mtn.admitiabff.domain.interview.ManualInterviewCreateRequest;
import cl.mtn.admitiabff.domain.person.ParentEntity;
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
import org.mockito.ArgumentCaptor;
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
            interviewerPairService,
            "KIV-2027-02"
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

        lenient().when(applicationRepository.findActiveById(120L)).thenReturn(Optional.of(application));
        lenient().when(userRepository.findById(10L)).thenReturn(Optional.of(firstInterviewer));
        lenient().when(userRepository.findById(11L)).thenReturn(Optional.of(secondInterviewer));
        lenient().when(interviewRepository.findByApplicationIdOrderByScheduledDateDesc(120L)).thenReturn(List.of());
        lenient().when(scheduleRepository.findAvailableTemplates(any(), any(), any(), any())).thenReturn(List.of());
        lenient().when(interviewRepository.findBlockingForInterviewer(any(), any(), anyList())).thenReturn(List.of());
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
        assertEquals("CONFIRMED", data.get("status"));
        ArgumentCaptor<InterviewEntity> savedInterview = ArgumentCaptor.forClass(InterviewEntity.class);
        verify(interviewRepository).save(savedInterview.capture());
        assertEquals(InterviewStatus.CONFIRMED, savedInterview.getValue().getStatus());
        assertEquals(InterviewStatus.CONFIRMED, savedInterview.getValue().getConfirmationStatus());

        when(interviewRepository.findByApplicationIdOrderByScheduledDateDesc(120L))
            .thenReturn(List.of(savedInterview.getValue()));
        when(interviewRepository.findByProcessAndScheduledDateBetween(
            "KIV-2027-02", LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31)
        )).thenReturn(List.of(savedInterview.getValue()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> applicationInterviews = (List<Map<String, Object>>) service.byApplication(120L).get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calendarInterviews = (List<Map<String, Object>>) service.calendar(
            "2026-08-31", "2026-08-31", false, "KIV-2027-02"
        ).get("data");
        assertEquals(List.of(901L), applicationInterviews.stream().map(item -> item.get("id")).toList());
        assertEquals(List.of(901L), calendarInterviews.stream().map(item -> item.get("id")).toList());
        verify(evaluationRepository).save(any(EvaluationEntity.class));
        verifyNoInteractions(emailComposerService);
    }

    @Test
    void keepsManualInterviewPendingGuardianConfirmationWhenEmailIsRequested() {
        when(userRepository.findById(99L)).thenReturn(Optional.of(admin));
        when(interviewRepository.save(any(InterviewEntity.class))).thenAnswer(invocation -> {
            InterviewEntity interview = invocation.getArgument(0);
            interview.setId(903L);
            return interview;
        });
        when(evaluationRepository.findByApplicationIdAndEvaluationType(120L, "FAMILY_INTERVIEW"))
            .thenReturn(Optional.empty());
        when(evaluationRepository.save(any(EvaluationEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> response = service.createManual(request("FAMILY", true, true), 99L);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals("SCHEDULED", data.get("status"));
        ArgumentCaptor<InterviewEntity> savedInterview = ArgumentCaptor.forClass(InterviewEntity.class);
        verify(interviewRepository).save(savedInterview.capture());
        assertEquals(InterviewStatus.SCHEDULED, savedInterview.getValue().getStatus());
        assertNull(savedInterview.getValue().getConfirmationStatus());
    }

    @Test
    void createsCompleteCycleDirectorFlowWithRoleBasedAssignmentsAndNoEmail() {
        firstInterviewer.setRole(Role.PSYCHOLOGIST);
        secondInterviewer.setRole(Role.CYCLE_DIRECTOR);
        when(userRepository.findById(99L)).thenReturn(Optional.of(admin));
        when(interviewRepository.save(any(InterviewEntity.class))).thenAnswer(invocation -> {
            InterviewEntity interview = invocation.getArgument(0);
            interview.setId(902L);
            return interview;
        });
        when(evaluationRepository.findByApplicationIdAndEvaluationType(any(), anyString()))
            .thenReturn(Optional.empty());
        when(evaluationRepository.save(any(EvaluationEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> response = service.createManual(request("CYCLE_DIRECTOR", true), 99L);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals("CYCLE_DIRECTOR", data.get("interviewType"));
        assertEquals(secondInterviewer.getId(), data.get("interviewerId"));
        assertEquals(firstInterviewer.getId(), data.get("secondInterviewerId"));

        ArgumentCaptor<EvaluationEntity> evaluations = ArgumentCaptor.forClass(EvaluationEntity.class);
        verify(evaluationRepository, org.mockito.Mockito.times(3)).save(evaluations.capture());
        Map<String, Long> evaluatorByType = evaluations.getAllValues().stream().collect(
            java.util.stream.Collectors.toMap(
                EvaluationEntity::getEvaluationType,
                evaluation -> evaluation.getEvaluator().getId()
            )
        );
        assertEquals(secondInterviewer.getId(), evaluatorByType.get("CYCLE_DIRECTOR_INTERVIEW"));
        assertEquals(secondInterviewer.getId(), evaluatorByType.get("CYCLE_DIRECTOR_REPORT"));
        assertEquals(firstInterviewer.getId(), evaluatorByType.get("PSYCHOLOGICAL_INTERVIEW"));
        verifyNoInteractions(emailComposerService);
    }

    @Test
    void rejectsCycleDirectorEntryWithoutRequiredRoles() {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> service.createManual(request("CYCLE_DIRECTOR", true), 99L)
        );

        assertEquals("Selecciona un director de ciclo activo", error.getMessage());
        verify(interviewRepository, never()).save(any());
        verifyNoInteractions(emailComposerService);
    }

    @Test
    void rescheduleKeepsInterviewActiveSyncsEvaluationAndNotifiesBothParents() {
        UserEntity replacementInterviewer = interviewer(12L, "Camila", "Rojas");
        when(userRepository.findById(12L)).thenReturn(Optional.of(replacementInterviewer));
        when(scheduleRepository.findAvailableTemplates(any(), any(), anyString(), any()))
            .thenReturn(List.of(schedule(LocalTime.of(8, 0), LocalTime.of(13, 0))));
        when(confirmationService.generateConfirmationUrl(anyString(), any(), org.mockito.ArgumentMatchers.eq(true)))
            .thenReturn("https://bff.test/confirm");
        when(confirmationService.generateConfirmationUrl(anyString(), any(), org.mockito.ArgumentMatchers.eq(false)))
            .thenReturn("https://bff.test/reject");

        ParentEntity father = parent("papa@example.cl");
        ParentEntity mother = parent("mama@example.cl");
        application.setFather(father);
        application.setMother(mother);

        InterviewEntity interview = new InterviewEntity();
        interview.setId(910L);
        interview.setApplication(application);
        interview.setInterviewType("FAMILY");
        interview.setInterviewer(firstInterviewer);
        interview.setSecondInterviewer(secondInterviewer);
        interview.setScheduledDate(LocalDate.of(2026, 8, 31));
        interview.setScheduledTime(LocalTime.of(9, 0));
        interview.setDuration(40);
        interview.setMode("IN_PERSON");
        interview.setStatus(InterviewStatus.CANCELLED);

        EvaluationEntity evaluation = new EvaluationEntity();
        evaluation.setId(77L);
        evaluation.setApplication(application);
        evaluation.setEvaluationType("FAMILY_INTERVIEW");
        evaluation.setEvaluator(firstInterviewer);
        evaluation.setEvaluationDate(LocalDate.of(2026, 8, 31).atTime(9, 0));
        evaluation.setStatus(EvaluationStatus.CANCELLED);

        when(interviewRepository.findById(910L)).thenReturn(Optional.of(interview));
        when(interviewRepository.save(interview)).thenReturn(interview);
        when(evaluationRepository.findByApplicationIdAndEvaluationType(120L, "FAMILY_INTERVIEW"))
            .thenReturn(Optional.of(evaluation));

        service.reschedule(910L, Map.of(
            "scheduledDate", "2026-09-01",
            "scheduledTime", "10:30",
            "interviewerId", 12L,
            "secondInterviewerId", 11L
        ), "https://bff.test");

        assertEquals(InterviewStatus.SCHEDULED, interview.getStatus());
        assertNull(interview.getConfirmationStatus());
        assertEquals(replacementInterviewer, evaluation.getEvaluator());
        assertEquals(LocalDate.of(2026, 9, 1).atTime(10, 30), evaluation.getEvaluationDate());
        assertEquals(EvaluationStatus.PENDING, evaluation.getStatus());

        ArgumentCaptor<EmailRequestDTO> emails = ArgumentCaptor.forClass(EmailRequestDTO.class);
        verify(emailComposerService, org.mockito.Mockito.times(2)).send(emails.capture());
        List<String> guardianEmails = emails.getAllValues().stream()
            .filter(email -> "APPLICATION".equals(email.recipientType))
            .map(email -> email.to)
            .toList();
        assertEquals(List.of("papa@example.cl", "mama@example.cl"), guardianEmails);
    }

    @Test
    void cancelStopsPendingEvaluationAssignmentFromRemainingActive() {
        InterviewEntity interview = new InterviewEntity();
        interview.setId(911L);
        interview.setApplication(application);
        interview.setInterviewType("FAMILY");
        interview.setInterviewer(firstInterviewer);
        interview.setSecondInterviewer(secondInterviewer);
        interview.setStatus(InterviewStatus.SCHEDULED);

        EvaluationEntity evaluation = new EvaluationEntity();
        evaluation.setId(78L);
        evaluation.setApplication(application);
        evaluation.setEvaluationType("FAMILY_INTERVIEW");
        evaluation.setEvaluator(firstInterviewer);
        evaluation.setStatus(EvaluationStatus.IN_PROGRESS);

        when(interviewRepository.findById(911L)).thenReturn(Optional.of(interview));
        when(interviewRepository.save(interview)).thenReturn(interview);
        when(evaluationRepository.findByApplicationIdAndEvaluationType(120L, "FAMILY_INTERVIEW"))
            .thenReturn(Optional.of(evaluation));

        service.cancel(911L, Map.of("reason", "Cambio solicitado por la familia"));

        assertEquals(InterviewStatus.CANCELLED, interview.getStatus());
        assertEquals(EvaluationStatus.CANCELLED, evaluation.getStatus());
        assertTrue(interview.getNotes().contains("Cambio solicitado"));
        verify(evaluationRepository).save(evaluation);
    }

    private ManualInterviewCreateRequest request(boolean confirmWarnings) {
        return request("FAMILY", confirmWarnings);
    }

    private ManualInterviewCreateRequest request(String interviewType, boolean confirmWarnings) {
        return request(interviewType, false, confirmWarnings);
    }

    private ManualInterviewCreateRequest request(String interviewType, boolean sendEmail, boolean confirmWarnings) {
        return new ManualInterviewCreateRequest(
            120L,
            interviewType,
            10L,
            11L,
            LocalDate.of(2026, 8, 31),
            LocalTime.of(13, 0),
            40,
            "IN_PERSON",
            null,
            "Corrección de una entrevista realizada fuera de agenda",
            sendEmail,
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

    private ParentEntity parent(String email) {
        ParentEntity parent = new ParentEntity();
        parent.setFullName("Apoderado " + email);
        parent.setEmail(email);
        parent.setParentType("GUARDIAN");
        return parent;
    }

    private InterviewerScheduleEntity schedule(LocalTime start, LocalTime end) {
        InterviewerScheduleEntity schedule = new InterviewerScheduleEntity();
        schedule.setStartTime(start);
        schedule.setEndTime(end);
        schedule.setYear(2026);
        schedule.setActive(true);
        return schedule;
    }
}
