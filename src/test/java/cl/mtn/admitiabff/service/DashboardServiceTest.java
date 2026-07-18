package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.DocumentRepository;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.GuardianRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.InterviewerScheduleRepository;
import cl.mtn.admitiabff.repository.NotificationRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardService - Admissions 2027 report endpoints")
class DashboardServiceTest {

    @Mock private ApplicationRepository applicationRepository;
    @Mock private EvaluationRepository evaluationRepository;
    @Mock private InterviewRepository interviewRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private UserRepository userRepository;
    @Mock private GuardianRepository guardianRepository;
    @Mock private InterviewerScheduleRepository interviewerScheduleRepository;
    @Mock private NotificationRepository notificationRepository;

    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(
            applicationRepository, userRepository, guardianRepository, notificationRepository,
            evaluationRepository, interviewRepository, interviewerScheduleRepository, documentRepository
        );
    }

    @Test
    @DisplayName("courseApplicants returns active applications filtered by academic year and ordered by grade")
    void testCourseApplicantsFilteringAndOrdering() {
        // Given
        ApplicationEntity app = new ApplicationEntity();
        app.setId(1L);
        app.setStatus(ApplicationStatus.PENDING);
        app.setSubmissionDate(LocalDateTime.of(2026, 5, 10, 10, 0));
        app.setDeletedAt(null);
        app.setArchived(false);

        StudentEntity student = new StudentEntity();
        student.setId(1L);
        student.setFirstName("Juan");
        student.setPaternalLastName("Pérez");
        student.setMaternalLastName("Soto");
        student.setGradeApplied("1º Básico");
        student.setGender("MALE");
        student.setAdmissionPreference("NINGUNA");
        app.setStudent(student);

        when(applicationRepository.findAll()).thenReturn(List.of(app));
        when(evaluationRepository.findByApplicationIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

        // When
        Map<String, Object> result = dashboardService.courseApplicants(2026);

        // Then
        assertTrue((Boolean) result.get("success"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        assertEquals(1, data.size());
        assertEquals("1º Básico", data.get(0).get("gradeApplied"));
        assertEquals("Nueva", data.get(0).get("admissionPreference"));
        assertEquals("No", data.get(0).get("siblingsInSchool"));
    }

    @Test
    @DisplayName("courseApplicants defaults to next calendar year when academicYear is null")
    void testCourseApplicantsDefaultYear() {
        int expectedYear = LocalDateTime.now().getYear() + 1;
        when(applicationRepository.findAll()).thenReturn(List.of());

        Map<String, Object> result = dashboardService.courseApplicants(null);

        assertTrue((Boolean) result.get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) result.get("meta");
        assertEquals(expectedYear, meta.get("academicYear"));
    }
}
