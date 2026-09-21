package cl.mtn.admitiabff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.FamilyEntity;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.ComplementaryFormRepository;
import cl.mtn.admitiabff.repository.DocumentRepository;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.FamilyRepository;
import cl.mtn.admitiabff.repository.GuardianRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.ParentRepository;
import cl.mtn.admitiabff.repository.StudentRepository;
import cl.mtn.admitiabff.repository.SupporterRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.util.JsonSupport;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** La segunda postulación de una misma cuenta apoderado no debe generar otro formulario familiar. */
class ApplicationServiceFamilyReuseTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final FamilyRepository familyRepository = mock(FamilyRepository.class);
    private final ApplicationService service = new ApplicationService(
        mock(ApplicationRepository.class),
        mock(StudentRepository.class),
        mock(ParentRepository.class),
        mock(GuardianRepository.class),
        mock(SupporterRepository.class),
        mock(UserRepository.class),
        mock(DocumentRepository.class),
        mock(ComplementaryFormRepository.class),
        mock(EvaluationRepository.class),
        mock(InterviewRepository.class),
        mock(AuthService.class),
        mock(NotificationService.class),
        mock(EmailComposerService.class),
        mock(JsonSupport.class),
        "uploads");

    ApplicationServiceFamilyReuseTest() {
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "familyRepository", familyRepository);
    }

    @Test
    void secondApplicationOfTheSameAccountReusesTheFamilyOfItsFirstApplication() {
        UserEntity account = account(173L);
        FamilyEntity existing = family(41L);
        when(jdbcTemplate.queryForList(contains("FROM applications"), eq(Long.class), eq(173L))).thenReturn(List.of(41L));
        when(jdbcTemplate.queryForList(contains("FROM family_members"), eq(Long.class), eq(173L))).thenReturn(List.of(99L));
        when(familyRepository.findById(41L)).thenReturn(Optional.of(existing));

        assertThat(service.resolveFamily(account)).isSameAs(existing);

        verify(familyRepository, never()).save(any(FamilyEntity.class));
        verify(jdbcTemplate).update(contains("INSERT INTO family_members"), eq(41L), eq(173L));
    }

    @Test
    void accountWithoutApplicationsReusesTheFamilyWhereItIsAlreadyAMember() {
        UserEntity account = account(173L);
        FamilyEntity associated = family(88L);
        when(jdbcTemplate.queryForList(contains("FROM applications"), eq(Long.class), eq(173L))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("FROM family_members"), eq(Long.class), eq(173L))).thenReturn(List.of(88L));
        when(familyRepository.findById(88L)).thenReturn(Optional.of(associated));

        assertThat(service.resolveFamily(account)).isSameAs(associated);

        verify(familyRepository, never()).save(any(FamilyEntity.class));
    }

    @Test
    void firstApplicationCreatesTheFamilyAndItsMembership() {
        UserEntity account = account(173L);
        FamilyEntity created = family(7L);
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(173L))).thenReturn(List.of());
        when(familyRepository.save(any(FamilyEntity.class))).thenReturn(created);

        assertThat(service.resolveFamily(account)).isSameAs(created);

        verify(jdbcTemplate).update(contains("INSERT INTO family_members"), eq(7L), eq(173L));
    }

    @Test
    void applicationWithoutAccountCreatesAFamilyWithoutMembership() {
        FamilyEntity created = family(9L);
        when(familyRepository.save(any(FamilyEntity.class))).thenReturn(created);

        assertThat(service.resolveFamily(null)).isSameAs(created);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void applicationsRegisteredByStaffDoNotShareTheStaffFamily() {
        UserEntity staff = new UserEntity();
        staff.setId(1L);
        staff.setRole(Role.ADMIN);
        FamilyEntity created = family(11L);
        when(familyRepository.save(any(FamilyEntity.class))).thenReturn(created);

        assertThat(service.resolveFamily(staff)).isSameAs(created);

        verify(familyRepository, never()).findById(anyLong());
        verify(jdbcTemplate, never()).queryForList(anyString(), eq(Long.class), anyLong());
    }

    @Test
    void prefersTheFamilyOfItsOwnApplicationsOverOtherMemberships() {
        assertThat(ApplicationService.familyToReuse(List.of(41L, 42L), List.of(99L))).isEqualTo(41L);
    }

    @Test
    void returnsNoFamilyWhenTheAccountHasNoApplicationsAndNoMemberships() {
        assertThat(ApplicationService.familyToReuse(List.of(), List.of())).isNull();
        assertThat(ApplicationService.familyToReuse(null, null)).isNull();
        assertThat(ApplicationService.familyToReuse(Arrays.asList(null, 42L), null)).isEqualTo(42L);
    }

    private static UserEntity account(Long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setRole(Role.APODERADO);
        return user;
    }

    private static FamilyEntity family(Long id) {
        FamilyEntity family = new FamilyEntity();
        family.setId(id);
        return family;
    }
}
