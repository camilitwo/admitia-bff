package cl.mtn.admitiabff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.person.ParentEntity;
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
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ParentBirthDateContractTest {
    private final ParentRepository parentRepository = mock(ParentRepository.class);
    private final ApplicationService service = new ApplicationService(
        mock(ApplicationRepository.class), mock(StudentRepository.class), parentRepository,
        mock(GuardianRepository.class), mock(SupporterRepository.class), mock(UserRepository.class),
        mock(DocumentRepository.class), mock(ComplementaryFormRepository.class),
        mock(EvaluationRepository.class), mock(InterviewRepository.class), mock(AuthService.class),
        mock(NotificationService.class), mock(EmailComposerService.class), mock(JsonSupport.class), "uploads");

    @Test
    void mapsFlatAndNestedBirthDates() {
        when(parentRepository.save(any(ParentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ParentEntity father = resolve(Map.of("parent1Name", "Padre", "parent1BirthDate", "1980-05-17"), "father", "parent1", "FATHER");
        ParentEntity mother = resolve(Map.of("mother", Map.of("fullName", "Madre", "birthDate", "1982-09-03")), "mother", "parent2", "MOTHER");
        assertThat(father.getBirthDate()).isEqualTo(LocalDate.of(1980, 5, 17));
        assertThat(mother.getBirthDate()).isEqualTo(LocalDate.of(1982, 9, 3));
    }

    @Test
    void rejectsMissingFutureAndUnderageDatesForNewParents() {
        assertInvalid(Map.of("parent1Name", "Padre"), "obligatoria");
        assertInvalid(Map.of("parent1Name", "Padre", "parent1BirthDate", LocalDate.now().plusDays(1).toString()), "futura");
        assertInvalid(Map.of("parent1Name", "Padre", "parent1BirthDate", LocalDate.now().minusYears(18).plusDays(1).toString()), "18 años");
    }

    @Test
    void existingParentWithoutBirthDateCannotBypassValidation() {
        ParentEntity historical = parent(10L, null);
        when(parentRepository.findById(10L)).thenReturn(Optional.of(historical));
        assertThatThrownBy(() -> resolve(Map.of("parent1Id", 10L), "father", "parent1", "FATHER"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("obligatoria");
    }

    @Test
    void suppliedDateCompletesAndPersistsAnExistingHistoricalParent() {
        ParentEntity historical = parent(10L, null);
        when(parentRepository.findById(10L)).thenReturn(Optional.of(historical));
        when(parentRepository.save(historical)).thenReturn(historical);
        ParentEntity resolved = resolve(Map.of("parent1Id", 10L, "parent1BirthDate", "1979-04-12"), "father", "parent1", "FATHER");
        assertThat(resolved.getBirthDate()).isEqualTo(LocalDate.of(1979, 4, 12));
        verify(parentRepository).save(historical);
    }

    @Test
    void editingExistingParentPersistsSeparatedProfileWithoutLosingBirthDate() {
        ParentEntity historical = parent(10L, LocalDate.of(1980, 1, 2));
        when(parentRepository.findById(10L)).thenReturn(Optional.of(historical));
        when(parentRepository.save(historical)).thenReturn(historical);

        ParentEntity updated = resolve(Map.of(
            "parent1Id", 10L,
            "father", Map.of(
                "firstName", "Juan",
                "paternalLastName", "Pérez",
                "maternalLastName", "Soto",
                "maritalStatus", "SEPARATED"
            )
        ), "father", "parent1", "FATHER");

        assertThat(updated.getFullName()).isEqualTo("Juan Pérez Soto");
        assertThat(updated.getMaritalStatus()).isEqualTo("SEPARATED");
        assertThat(updated.getBirthDate()).isEqualTo(LocalDate.of(1980, 1, 2));
        verify(parentRepository).save(historical);
    }

    @Test
    void existingValidDateIsUsedWhenPayloadDoesNotReplaceIt() {
        ParentEntity existing = parent(10L, LocalDate.of(1980, 1, 2));
        when(parentRepository.findById(10L)).thenReturn(Optional.of(existing));
        assertThat(resolve(Map.of("parent1Id", 10L), "father", "parent1", "FATHER")).isSameAs(existing);
    }

    @Test
    void responseIncludesBirthDateAndLegacyNullRemainsReadable() {
        assertThat(response(parent(10L, LocalDate.of(1980, 1, 2)))).containsEntry("birthDate", LocalDate.of(1980, 1, 2));
        assertThat(response(parent(11L, null))).containsEntry("birthDate", null);
    }

    @Test
    void mapsSeparatedParentNamesAndKeepsLegacyFullName() {
        when(parentRepository.save(any(ParentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ParentEntity father = resolve(Map.of("father", Map.of(
            "firstName", "Juan Pablo",
            "paternalLastName", "Pérez",
            "maternalLastName", "Soto",
            "maritalStatus", "MARRIED",
            "rut", "11.111.111-1",
            "birthDate", "1980-05-17"
        )), "father", "parent1", "FATHER");

        assertThat(father.getFirstName()).isEqualTo("Juan Pablo");
        assertThat(father.getPaternalLastName()).isEqualTo("Pérez");
        assertThat(father.getMaternalLastName()).isEqualTo("Soto");
        assertThat(father.getMaritalStatus()).isEqualTo("MARRIED");
        assertThat(father.getFullName()).isEqualTo("Juan Pablo Pérez Soto");
        assertThat(response(father))
            .containsEntry("firstName", "Juan Pablo")
            .containsEntry("paternalLastName", "Pérez")
            .containsEntry("maternalLastName", "Soto")
            .containsEntry("maritalStatus", "MARRIED")
            .containsEntry("fullName", "Juan Pablo Pérez Soto");
    }

    private void assertInvalid(Map<String, Object> payload, String message) {
        assertThatThrownBy(() -> resolve(payload, "father", "parent1", "FATHER"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    private ParentEntity resolve(Map<String, Object> payload, String nestedKey, String flatPrefix, String type) {
        return ReflectionTestUtils.invokeMethod(service, "resolveParent", payload, nestedKey, flatPrefix, type, true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> response(ParentEntity parent) {
        return ReflectionTestUtils.invokeMethod(service, "toParentResponse", parent, parent.getParentType());
    }

    private static ParentEntity parent(long id, LocalDate birthDate) {
        ParentEntity parent = new ParentEntity();
        parent.setId(id);
        parent.setFullName("Adulto");
        parent.setParentType("FATHER");
        parent.setBirthDate(birthDate);
        return parent;
    }
}
