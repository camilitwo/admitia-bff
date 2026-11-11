package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.evaluation.EvaluationEntity;
import cl.mtn.admitiabff.domain.interview.InterviewEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfessionalActivityServiceTest {

    @Test
    void countsAcademicEvaluationsForActiveTeacherInRequestedProcess() {
        UserRepository userRepository = mock(UserRepository.class);
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        InterviewRepository interviewRepository = mock(InterviewRepository.class);

        UserEntity teacher = professional(10L, "Ana", "Pérez", Role.TEACHER, true);
        ApplicationEntity application = application(100L, "KIV-2027-02");
        EvaluationEntity completed = evaluation(201L, application, teacher, "LANGUAGE_EXAM", EvaluationStatus.COMPLETED);
        EvaluationEntity pending = evaluation(202L, application, teacher, "MATHEMATICS_EXAM", EvaluationStatus.PENDING);
        EvaluationEntity cancelled = evaluation(203L, application, teacher, "ENGLISH_EXAM", EvaluationStatus.CANCELLED);

        when(userRepository.findByRoleInOrderByRoleAscFirstNameAscLastNameAsc(List.of(
            Role.TEACHER, Role.PSYCHOLOGIST, Role.CYCLE_DIRECTOR, Role.INTERVIEWER
        ))).thenReturn(List.of(teacher));
        when(evaluationRepository.findActiveByProcessOrderByCreatedAtDesc("KIV-2027-02"))
            .thenReturn(List.of(completed, pending, cancelled));
        when(interviewRepository.findActiveByProcessOrderByCreatedAtDesc("KIV-2027-02"))
            .thenReturn(List.of());

        ProfessionalActivityService service = new ProfessionalActivityService(
            userRepository, evaluationRepository, interviewRepository, "KIV-2027-02"
        );

        var result = service.getActivity("KIV-2027-02", null);
        var activity = result.professionals().getFirst();

        assertEquals("KIV-2027-02", result.processCode());
        assertEquals(10L, activity.userId());
        assertEquals(2, activity.assigned());
        assertEquals(1, activity.completed());
        assertEquals(1, activity.pending());
        assertEquals(0, activity.inProgress());
        assertEquals(1, activity.cancelled());
        assertEquals(50.0, activity.completionRate());
        assertEquals(2, activity.evaluations().assigned());
        assertEquals(0, activity.interviews().assigned());
        assertEquals(0, activity.reports().assigned());
    }

    @Test
    void creditsCompletedFamilyInterviewToBothInterviewersWithoutDuplicatingEvaluation() {
        UserRepository userRepository = mock(UserRepository.class);
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        InterviewRepository interviewRepository = mock(InterviewRepository.class);

        UserEntity first = professional(20L, "Beatriz", "Soto", Role.INTERVIEWER, true);
        UserEntity second = professional(21L, "Carlos", "Díaz", Role.INTERVIEWER, true);
        ApplicationEntity application = application(101L, "KIV-2027-02");
        EvaluationEntity familyEvaluation = evaluation(
            204L, application, first, "FAMILY_INTERVIEW", EvaluationStatus.COMPLETED
        );
        InterviewEntity familyInterview = interview(
            301L, application, first, second, "FAMILY", InterviewStatus.CONFIRMED
        );

        when(userRepository.findByRoleInOrderByRoleAscFirstNameAscLastNameAsc(List.of(
            Role.TEACHER, Role.PSYCHOLOGIST, Role.CYCLE_DIRECTOR, Role.INTERVIEWER
        ))).thenReturn(List.of(first, second));
        when(evaluationRepository.findActiveByProcessOrderByCreatedAtDesc("KIV-2027-02"))
            .thenReturn(List.of(familyEvaluation));
        when(interviewRepository.findActiveByProcessOrderByCreatedAtDesc("KIV-2027-02"))
            .thenReturn(List.of(familyInterview));

        ProfessionalActivityService service = new ProfessionalActivityService(
            userRepository, evaluationRepository, interviewRepository, "KIV-2027-02"
        );

        var professionals = service.getActivity("KIV-2027-02", null).professionals();

        assertEquals(2, professionals.size());
        for (var activity : professionals) {
            assertEquals(1, activity.assigned());
            assertEquals(1, activity.completed());
            assertEquals(1, activity.interviews().assigned());
            assertEquals(1, activity.interviews().completed());
            assertEquals(0, activity.evaluations().assigned());
            assertEquals(1, activity.breakdown().get("FAMILY_INTERVIEW").completed());
        }
    }

    @Test
    void separatesCycleDirectorReportAndDoesNotCountCycleInterviewRowTwice() {
        UserRepository userRepository = mock(UserRepository.class);
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        InterviewRepository interviewRepository = mock(InterviewRepository.class);

        UserEntity director = professional(30L, "Daniel", "Rojas", Role.CYCLE_DIRECTOR, true);
        UserEntity psychologist = professional(31L, "Elena", "Silva", Role.PSYCHOLOGIST, true);
        ApplicationEntity application = application(102L, "KIV-2027-02");
        EvaluationEntity directorInterview = evaluation(
            205L, application, director, "CYCLE_DIRECTOR_INTERVIEW", EvaluationStatus.COMPLETED
        );
        EvaluationEntity directorReport = evaluation(
            206L, application, director, "CYCLE_DIRECTOR_REPORT", EvaluationStatus.PENDING
        );
        EvaluationEntity psychologicalInterview = evaluation(
            207L, application, psychologist, "PSYCHOLOGICAL_INTERVIEW", EvaluationStatus.COMPLETED
        );
        InterviewEntity scheduledPair = interview(
            302L, application, director, psychologist, "CYCLE_DIRECTOR", InterviewStatus.CONFIRMED
        );

        when(userRepository.findByRoleInOrderByRoleAscFirstNameAscLastNameAsc(List.of(
            Role.TEACHER, Role.PSYCHOLOGIST, Role.CYCLE_DIRECTOR, Role.INTERVIEWER
        ))).thenReturn(List.of(director, psychologist));
        when(evaluationRepository.findActiveByProcessOrderByCreatedAtDesc("KIV-2027-02"))
            .thenReturn(List.of(directorInterview, directorReport, psychologicalInterview));
        when(interviewRepository.findActiveByProcessOrderByCreatedAtDesc("KIV-2027-02"))
            .thenReturn(List.of(scheduledPair));

        ProfessionalActivityService service = new ProfessionalActivityService(
            userRepository, evaluationRepository, interviewRepository, "KIV-2027-02"
        );

        var professionals = service.getActivity("KIV-2027-02", null).professionals();
        var directorActivity = professionals.stream().filter(item -> item.userId() == 30L).findFirst().orElseThrow();
        var psychologistActivity = professionals.stream().filter(item -> item.userId() == 31L).findFirst().orElseThrow();

        assertEquals(1, directorActivity.assigned());
        assertEquals(1, directorActivity.completed());
        assertEquals(1, directorActivity.interviews().assigned());
        assertEquals(1, directorActivity.reports().assigned());
        assertEquals(0, directorActivity.evaluations().assigned());
        assertEquals(1, psychologistActivity.assigned());
        assertEquals(1, psychologistActivity.completed());
        assertEquals(1, psychologistActivity.interviews().assigned());
        assertEquals(0, psychologistActivity.reports().assigned());
    }

    @Test
    void reportsFamilyRejectionAsNoShowInsteadOfCancellation() {
        UserRepository userRepository = mock(UserRepository.class);
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        InterviewRepository interviewRepository = mock(InterviewRepository.class);

        UserEntity interviewer = professional(40L, "Fernanda", "Lagos", Role.INTERVIEWER, true);
        ApplicationEntity application = application(103L, "KIV-2027-02");
        InterviewEntity rejected = interview(
            303L, application, interviewer, null, "FAMILY", InterviewStatus.REJECTED_BY_FAMILY
        );

        stubActivityData(userRepository, evaluationRepository, interviewRepository, interviewer, List.of(rejected));

        var activity = new ProfessionalActivityService(
            userRepository, evaluationRepository, interviewRepository, "KIV-2027-02"
        ).getActivity("KIV-2027-02", null).professionals().getFirst();

        assertEquals(0, activity.assigned());
        assertEquals(0, activity.cancelled());
        assertEquals(1, activity.noShow());
        assertEquals(1, activity.interviews().noShow());
    }

    @Test
    void reportsRescheduledFamilyInterviewAsPendingAssignment() {
        UserRepository userRepository = mock(UserRepository.class);
        EvaluationRepository evaluationRepository = mock(EvaluationRepository.class);
        InterviewRepository interviewRepository = mock(InterviewRepository.class);

        UserEntity interviewer = professional(41L, "Gabriel", "Mora", Role.INTERVIEWER, true);
        ApplicationEntity application = application(104L, "KIV-2027-02");
        InterviewEntity rescheduled = interview(
            304L, application, interviewer, null, "FAMILY", InterviewStatus.RESCHEDULED
        );

        stubActivityData(userRepository, evaluationRepository, interviewRepository, interviewer, List.of(rescheduled));

        var activity = new ProfessionalActivityService(
            userRepository, evaluationRepository, interviewRepository, "KIV-2027-02"
        ).getActivity("KIV-2027-02", null).professionals().getFirst();

        assertEquals(1, activity.assigned());
        assertEquals(1, activity.pending());
        assertEquals(0, activity.cancelled());
        assertEquals(0, activity.noShow());
    }

    private static void stubActivityData(
        UserRepository userRepository,
        EvaluationRepository evaluationRepository,
        InterviewRepository interviewRepository,
        UserEntity professional,
        List<InterviewEntity> interviews
    ) {
        when(userRepository.findByRoleInOrderByRoleAscFirstNameAscLastNameAsc(List.of(
            Role.TEACHER, Role.PSYCHOLOGIST, Role.CYCLE_DIRECTOR, Role.INTERVIEWER
        ))).thenReturn(List.of(professional));
        when(evaluationRepository.findActiveByProcessOrderByCreatedAtDesc("KIV-2027-02"))
            .thenReturn(List.of());
        when(interviewRepository.findActiveByProcessOrderByCreatedAtDesc("KIV-2027-02"))
            .thenReturn(interviews);
    }

    private static UserEntity professional(Long id, String firstName, String lastName, Role role, boolean active) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmail(firstName.toLowerCase() + "@mtn.cl");
        user.setRole(role);
        user.setActive(active);
        return user;
    }

    private static ApplicationEntity application(Long id, String processCode) {
        ApplicationEntity application = new ApplicationEntity();
        application.setId(id);
        application.setProcessCode(processCode);
        application.setArchived(false);
        return application;
    }

    private static EvaluationEntity evaluation(
        Long id,
        ApplicationEntity application,
        UserEntity evaluator,
        String type,
        EvaluationStatus status
    ) {
        EvaluationEntity evaluation = new EvaluationEntity();
        evaluation.setId(id);
        evaluation.setApplication(application);
        evaluation.setEvaluator(evaluator);
        evaluation.setEvaluationType(type);
        evaluation.setStatus(status);
        return evaluation;
    }

    private static InterviewEntity interview(
        Long id,
        ApplicationEntity application,
        UserEntity first,
        UserEntity second,
        String type,
        InterviewStatus status
    ) {
        InterviewEntity interview = new InterviewEntity();
        interview.setId(id);
        interview.setApplication(application);
        interview.setInterviewer(first);
        interview.setSecondInterviewer(second);
        interview.setInterviewType(type);
        interview.setStatus(status);
        return interview;
    }
}
