package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.InterviewerPairRepository;
import cl.mtn.admitiabff.repository.InterviewerScheduleRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewerPairServiceTest {
    @Mock InterviewerPairRepository pairRepository;
    @Mock UserRepository userRepository;
    @Mock ApplicationRepository applicationRepository;
    @Mock InterviewRepository interviewRepository;
    @Mock InterviewerScheduleRepository scheduleRepository;

    @Test
    void normalizesCanonicalAndRomanGradeAliases() {
        assertEquals("PRE_KINDER", InterviewerPairService.normalizeGrade("Prekínder"));
        assertEquals("1_BASICO", InterviewerPairService.normalizeGrade("1° Básico"));
        assertEquals("1_MEDIO", InterviewerPairService.normalizeGrade("I_MEDIO"));
        assertEquals("4_MEDIO", InterviewerPairService.normalizeGrade("IV Medio"));
        assertEquals(null, InterviewerPairService.normalizeGrade("Universidad"));
    }

    @Test
    void rejectsMemberWithWrongRole() {
        InterviewerPairService service = service();
        UserEntity wrongDirector = user(1L, Role.TEACHER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(wrongDirector));

        InterviewerPairException error = assertThrows(InterviewerPairException.class, () -> service.create(Map.of(
            "cycleDirectorId", 1L,
            "psychologistId", 2L,
            "grades", List.of("KINDER")
        )));

        assertEquals("PAIR_MEMBER_ROLE_INVALID", error.getCode());
    }

    @Test
    void explainsWhenNoPairsAreConfiguredForValidApplication() {
        InterviewerPairService service = service();
        StudentEntity student = new StudentEntity();
        student.setGradeApplied("2° Básico");
        ApplicationEntity application = new ApplicationEntity();
        application.setId(20L);
        application.setStudent(student);
        when(applicationRepository.findActiveById(20L)).thenReturn(Optional.of(application));
        when(pairRepository.findByActiveTrueOrderByCycleDirectorFirstNameAscCycleDirectorLastNameAsc()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) service.eligible(20L, null, null, 60).get("data");

        assertEquals("2_BASICO", data.get("grade"));
        assertEquals("NO_PAIRS_CONFIGURED", data.get("reasonCode"));
    }

    @Test
    void requiresConfiguredPairForCycleDirectorInterview() {
        InterviewerPairException error = assertThrows(InterviewerPairException.class, () ->
            service().requireEligiblePair(null, 20L, null, null, 60, null)
        );

        assertEquals("PAIR_REQUIRED", error.getCode());
    }

    @Test
    void reservesCycleDirectorAndPsychologistRolesForCycleInterviews() {
        assertEquals(true, InterviewService.isCycleInterviewRole(Role.CYCLE_DIRECTOR));
        assertEquals(true, InterviewService.isCycleInterviewRole(Role.PSYCHOLOGIST));
        assertEquals(false, InterviewService.isCycleInterviewRole(Role.INTERVIEWER));
        assertEquals(false, InterviewService.isCycleInterviewRole(Role.COORDINATOR));
    }

    private InterviewerPairService service() {
        return new InterviewerPairService(pairRepository, userRepository, applicationRepository, interviewRepository, scheduleRepository);
    }

    private UserEntity user(Long id, Role role) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setFirstName("Nombre");
        user.setLastName("Apellido");
        user.setRole(role);
        user.setActive(true);
        return user;
    }
}
