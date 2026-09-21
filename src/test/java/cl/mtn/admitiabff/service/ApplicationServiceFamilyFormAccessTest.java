package cl.mtn.admitiabff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.application.ComplementaryFormEntity;
import cl.mtn.admitiabff.domain.application.FamilyEntity;
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
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.util.JsonSupport;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** Los equipos internos deben poder ver el cuestionario de los padres; los apoderados ajenos no. */
class ApplicationServiceFamilyFormAccessTest {
    private final ApplicationRepository applicationRepository = mock(ApplicationRepository.class);
    private final ComplementaryFormRepository complementaryFormRepository = mock(ComplementaryFormRepository.class);
    private final AuthService authService = mock(AuthService.class);
    private final JsonSupport jsonSupport = mock(JsonSupport.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ApplicationService service = new ApplicationService(
        applicationRepository,
        mock(StudentRepository.class),
        mock(ParentRepository.class),
        mock(GuardianRepository.class),
        mock(SupporterRepository.class),
        mock(UserRepository.class),
        mock(DocumentRepository.class),
        complementaryFormRepository,
        mock(EvaluationRepository.class),
        mock(InterviewRepository.class),
        authService,
        mock(NotificationService.class),
        mock(EmailComposerService.class),
        jsonSupport,
        "uploads");

    private ApplicationEntity application;

    ApplicationServiceFamilyFormAccessTest() {
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
    }

    @BeforeEach
    void setUp() {
        FamilyEntity family = new FamilyEntity();
        family.setId(40L);
        application = new ApplicationEntity();
        application.setId(56L);
        application.setFamily(family);
        application.setAcademicYear(2027);
        application.setSubmissionDate(LocalDateTime.of(2026, 9, 1, 10, 0));
    }

    @Test
    void interviewerReadsTheParentQuestionnaireWithoutBelongingToTheFamily() {
        stubbedActorsRole("INTERVIEWER");
        stubbedQuestionnaire();

        Map<String, Object> response = service.complementaryForm(56L);
        Map<?, ?> data = (Map<?, ?>) response.get("data");

        assertThat(response).containsEntry("success", true);
        assertThat(data.get("familyId")).isEqualTo(40L);
        assertThat(data.get("isSubmitted")).isEqualTo(true);
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Integer.class), any(), any());
    }

    @Test
    void teacherReadsTheParentQuestionnaireWithoutBelongingToTheFamily() {
        stubbedActorsRole("TEACHER");
        stubbedQuestionnaire();

        assertThat(service.complementaryForm(56L)).containsEntry("success", true);

        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Integer.class), any(), any());
    }

    @Test
    void guardianOutsideTheFamilyStillCannotReadTheQuestionnaire() {
        stubbedActorsRole("APODERADO");
        when(applicationRepository.findActiveById(56L)).thenReturn(Optional.of(application));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.complementaryForm(56L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("No pertenece al grupo familiar");

        verify(complementaryFormRepository, never()).findByFamilyIdAndProcessKey(anyLong(), anyString());
    }

    @Test
    void readerRolesAreExplicitAndFailClosed() {
        assertThat(ApplicationService.canReadFamilyQuestionnaire("INTERVIEWER")).isTrue();
        assertThat(ApplicationService.canReadFamilyQuestionnaire("interviewer")).isTrue();
        assertThat(ApplicationService.canReadFamilyQuestionnaire("TEACHER")).isTrue();
        assertThat(ApplicationService.canReadFamilyQuestionnaire("PSYCHOLOGIST")).isTrue();
        assertThat(ApplicationService.canReadFamilyQuestionnaire("PREKINDER_PROFESSIONAL")).isTrue();
        assertThat(ApplicationService.canReadFamilyQuestionnaire("APODERADO")).isFalse();
        assertThat(ApplicationService.canReadFamilyQuestionnaire(null)).isFalse();
        assertThat(ApplicationService.canReadFamilyQuestionnaire("VISITANTE")).isFalse();
    }

    private void stubbedActorsRole(String role) {
        when(authService.requireAuth()).thenReturn(new AuthService.AuthContextHolder(98L, "profesional@mtn.cl", role));
        when(applicationRepository.findActiveById(56L)).thenReturn(Optional.of(application));
    }

    private void stubbedQuestionnaire() {
        ComplementaryFormEntity form = new ComplementaryFormEntity();
        form.setId(500L);
        form.setApplication(application);
        form.setFamily(application.getFamily());
        form.setProcessKey("GENERAL:2027");
        form.setSubmitted(true);
        form.setFormData("{\"familyValues\":\"Compromiso\"}");
        when(complementaryFormRepository.findByFamilyIdAndProcessKey(anyLong(), anyString())).thenReturn(Optional.of(form));
        when(applicationRepository.findByFamilyIdAndAcademicYearAndDeletedAtIsNullOrderByCreatedAtAsc(anyLong(), any()))
            .thenReturn(List.of());
        when(jsonSupport.readMap(any())).thenReturn(new LinkedHashMap<String, Object>());
    }
}
