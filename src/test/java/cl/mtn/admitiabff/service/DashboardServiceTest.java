package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.application.ComplementaryFormEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.common.DocumentType;
import cl.mtn.admitiabff.domain.document.DocumentEntity;
import cl.mtn.admitiabff.domain.evaluation.EvaluationEntity;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.ComplementaryFormRepository;
import cl.mtn.admitiabff.repository.DocumentRepository;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.GuardianRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.InterviewerScheduleRepository;
import cl.mtn.admitiabff.repository.NotificationRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.util.JsonSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    @Mock private ComplementaryFormRepository complementaryFormRepository;
    @Mock private JsonSupport jsonSupport;

    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(
            applicationRepository, userRepository, guardianRepository, notificationRepository,
            evaluationRepository, interviewRepository, interviewerScheduleRepository, documentRepository,
            complementaryFormRepository, jsonSupport
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
        app.setAcademicYear(2026);
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

    @Test
    @DisplayName("applicantCard exposes the complete cycle director interview report")
    void testApplicantCardCycleDirectorReport() {
        ApplicationEntity app = new ApplicationEntity();
        app.setId(7L);
        app.setStatus(ApplicationStatus.PENDING);
        app.setSubmissionDate(LocalDateTime.of(2026, 6, 1, 9, 0));

        StudentEntity student = new StudentEntity();
        student.setId(3L);
        student.setFirstName("Elena");
        student.setPaternalLastName("Rojas");
        student.setMaternalLastName("Díaz");
        student.setGradeApplied("1º Básico");
        student.setAdmissionPreference("NINGUNA");
        app.setStudent(student);

        UserEntity evaluator = new UserEntity();
        evaluator.setFirstName("María");
        evaluator.setLastName("Soto");

        EvaluationEntity interview = new EvaluationEntity();
        interview.setEvaluationType("CYCLE_DIRECTOR_INTERVIEW");
        interview.setStatus(EvaluationStatus.COMPLETED);
        interview.setEvaluationDate(LocalDateTime.of(2026, 6, 15, 11, 30));
        interview.setObservations("Antecedentes y desarrollo completo");
        interview.setRecommendations("Observaciones finales de la entrevista");
        interview.setAreasForImprovement("Aspectos a acompañar");
        interview.setEvaluator(evaluator);

        EvaluationEntity directorReport = new EvaluationEntity();
        directorReport.setEvaluationType("CYCLE_DIRECTOR_REPORT");
        directorReport.setStatus(EvaluationStatus.COMPLETED);
        directorReport.setRecommendations("Informe final\n\nDecisión Final: Aceptado");

        when(applicationRepository.findActiveById(7L)).thenReturn(Optional.of(app));
        when(evaluationRepository.findByApplicationIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(directorReport, interview));
        when(interviewRepository.findByApplicationIdOrderByScheduledDateDesc(7L)).thenReturn(List.of());
        when(documentRepository.findByApplicationIdOrderByUploadDateDesc(7L)).thenReturn(List.of());

        Map<String, Object> result = dashboardService.applicantCard(7L);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> cycleDirector = (Map<String, Object>) data.get("cycleDirector");
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) cycleDirector.get("report");

        assertEquals("Aceptado", cycleDirector.get("decision"));
        assertEquals("Antecedentes y desarrollo completo", report.get("observations"));
        assertEquals("Observaciones finales de la entrevista", report.get("recommendations"));
        assertEquals("Aspectos a acompañar", report.get("areasForImprovement"));
        assertEquals("María Soto", report.get("evaluator"));
    }

    @Test
    @DisplayName("applicantCard keeps a legacy questionnaire attachment when no digital form exists")
    void testApplicantCardQuestionnaireNotStartedWithLegacyAttachment() {
        ApplicationEntity app = application(8L);
        DocumentEntity document = new DocumentEntity();
        document.setDocumentType(DocumentType.OTHER);
        document.setFileName("cuestionario-familia.pdf");
        document.setOriginalName("Cuestionario familia.pdf");
        document.setFilePath("/uploads/cuestionario-familia.pdf");
        stubApplicantCard(app, List.of(document));
        when(complementaryFormRepository.findByApplicationId(8L)).thenReturn(Optional.empty());

        Map<String, Object> result = dashboardService.applicantCard(8L);

        Map<String, Object> questionnaire = questionnaire(result);
        assertEquals("NOT_STARTED", questionnaire.get("status"));
        assertEquals(true, questionnaire.get("received"));
        assertEquals("/uploads/cuestionario-familia.pdf", questionnaire.get("reportLink"));
        assertFalse(questionnaire.containsKey("answers"));
    }

    @Test
    @DisplayName("applicantCard reports a draft without exposing its partial answers")
    void testApplicantCardQuestionnaireDraftHidesAnswers() {
        ApplicationEntity app = application(9L);
        ComplementaryFormEntity form = questionnaireForm(app, false);
        form.setFormData("{\"applicationReasons\":\"Respuesta parcial\"}");
        stubApplicantCard(app, List.of());
        when(complementaryFormRepository.findByApplicationId(9L)).thenReturn(Optional.of(form));

        Map<String, Object> result = dashboardService.applicantCard(9L);

        Map<String, Object> questionnaire = questionnaire(result);
        assertEquals("DRAFT", questionnaire.get("status"));
        assertEquals(false, questionnaire.get("received"));
        assertEquals(form.getUpdatedAt(), questionnaire.get("updatedAt"));
        assertFalse(questionnaire.containsKey("answers"));
    }

    @Test
    @DisplayName("applicantCard exposes every submitted answer using canonical camelCase keys")
    void testApplicantCardSubmittedQuestionnaireNormalizesCurrentAndLegacyKeys() {
        ApplicationEntity app = application(10L);
        ComplementaryFormEntity form = questionnaireForm(app, true);
        form.setFormData("stored-json");
        Map<String, Object> storedAnswers = Map.ofEntries(
            Map.entry("otherSchools", "Colegio A"),
            Map.entry("father_name", "Padre Ejemplo"),
            Map.entry("fatherEducation", "Universitaria"),
            Map.entry("father_current_activity", "Arquitecto"),
            Map.entry("motherName", "Madre Ejemplo"),
            Map.entry("mother_education", "Técnica"),
            Map.entry("motherCurrentActivity", "Emprendedora"),
            Map.entry("application_reasons", "Proyecto educativo\nComunidad"),
            Map.entry("schoolChangeReason", "Cambio de ciclo"),
            Map.entry("family_values", "Respeto y solidaridad"),
            Map.entry("faithExperiences", "Oración en familia"),
            Map.entry("community_service_experiences", "Voluntariado"),
            Map.entry("children_descriptions", List.of(
                Map.of("childName", "Ana", "description", "Curiosa", "dream", "Que sea feliz"),
                Map.of("child_name", "Luis", "description", "Creativo", "dream", "Que encuentre su vocación")
            ))
        );
        stubApplicantCard(app, List.of());
        when(complementaryFormRepository.findByApplicationId(10L)).thenReturn(Optional.of(form));
        when(jsonSupport.readMap("stored-json")).thenReturn(storedAnswers);

        Map<String, Object> result = dashboardService.applicantCard(10L);

        Map<String, Object> questionnaire = questionnaire(result);
        assertEquals("SUBMITTED", questionnaire.get("status"));
        assertEquals(true, questionnaire.get("received"));
        assertEquals(form.getSubmittedAt(), questionnaire.get("submittedAt"));
        @SuppressWarnings("unchecked")
        Map<String, Object> answers = (Map<String, Object>) questionnaire.get("answers");
        assertEquals("Colegio A", answers.get("otherSchools"));
        assertEquals("Padre Ejemplo", answers.get("fatherName"));
        assertEquals("Arquitecto", answers.get("fatherCurrentActivity"));
        assertEquals("Técnica", answers.get("motherEducation"));
        assertEquals("Proyecto educativo\nComunidad", answers.get("applicationReasons"));
        assertEquals("Voluntariado", answers.get("communityServiceExperiences"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) answers.get("childrenDescriptions");
        assertEquals(2, children.size());
        assertEquals("Ana", children.get(0).get("childName"));
        assertEquals("Luis", children.get(1).get("childName"));
    }

    private ApplicationEntity application(Long id) {
        ApplicationEntity app = new ApplicationEntity();
        app.setId(id);
        app.setStatus(ApplicationStatus.PENDING);
        app.setSubmissionDate(LocalDateTime.of(2026, 6, 1, 9, 0));
        StudentEntity student = new StudentEntity();
        student.setId(id);
        student.setFirstName("Postulante");
        student.setPaternalLastName("Prueba");
        student.setMaternalLastName("MTN");
        student.setGradeApplied("1º Básico");
        student.setAdmissionPreference("NINGUNA");
        app.setStudent(student);
        return app;
    }

    private ComplementaryFormEntity questionnaireForm(ApplicationEntity app, boolean submitted) {
        ComplementaryFormEntity form = new ComplementaryFormEntity();
        form.setId(app.getId() * 10);
        form.setApplication(app);
        form.setSubmitted(submitted);
        form.setCreatedAt(LocalDateTime.of(2026, 6, 2, 10, 0));
        form.setUpdatedAt(LocalDateTime.of(2026, 6, 3, 11, 0));
        form.setSubmittedAt(submitted ? LocalDateTime.of(2026, 6, 3, 11, 0) : null);
        return form;
    }

    private void stubApplicantCard(ApplicationEntity app, List<DocumentEntity> documents) {
        when(applicationRepository.findActiveById(app.getId())).thenReturn(Optional.of(app));
        when(evaluationRepository.findByApplicationIdOrderByCreatedAtDesc(app.getId())).thenReturn(List.of());
        when(interviewRepository.findByApplicationIdOrderByScheduledDateDesc(app.getId())).thenReturn(List.of());
        when(documentRepository.findByApplicationIdOrderByUploadDateDesc(app.getId())).thenReturn(documents);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> questionnaire(Map<String, Object> result) {
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        return (Map<String, Object>) data.get("familyQuestionnaire");
    }
}
