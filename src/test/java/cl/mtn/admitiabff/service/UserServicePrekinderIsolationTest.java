package cl.mtn.admitiabff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.InterviewerPairRepository;
import cl.mtn.admitiabff.repository.InterviewerScheduleRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.util.JsonSupport;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UserServicePrekinderIsolationTest {
    private final UserRepository users = mock(UserRepository.class);
    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(users, mock(EvaluationRepository.class), mock(InterviewRepository.class),
            mock(InterviewerScheduleRepository.class), mock(InterviewerPairRepository.class),
            mock(AuthService.class), mock(JsonSupport.class));
    }

    @Test
    void generalRoleCatalogKeepsAllExistingRolesAndHidesOnlyPrekinderProfessional() {
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) service.roles().get("roles");

        assertThat(roles).contains(Role.ADMIN.name(), Role.TEACHER.name(), Role.PSYCHOLOGIST.name(),
            Role.COORDINATOR.name(), Role.APODERADO.name());
        assertThat(roles).doesNotContain(Role.PREKINDER_PROFESSIONAL.name());
        assertThat(roles).hasSize(Role.values().length - 1);
    }

    @Test
    void generalStaffLookupExcludesGuardianAndPrekinderOnlyRoles() {
        when(users.findByRoleInOrderByRoleAscFirstNameAscLastNameAsc(anyCollection())).thenReturn(List.of());

        service.publicSchoolStaff(true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Role>> roles = ArgumentCaptor.forClass(Collection.class);
        verify(users).findByRoleInOrderByRoleAscFirstNameAscLastNameAsc(roles.capture());
        assertThat(roles.getValue()).contains(Role.ADMIN, Role.TEACHER, Role.PSYCHOLOGIST, Role.COORDINATOR);
        assertThat(roles.getValue()).doesNotContain(Role.APODERADO, Role.PREKINDER_PROFESSIONAL);
    }

    @Test
    void directGeneralRoleLookupDoesNotExposePrekinderAccounts() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) service
            .byRole(Role.PREKINDER_PROFESSIONAL.name(), true).get("data");

        assertThat(result).isEmpty();
    }

    @Test
    void generalStatisticsExcludeOnlyPrekinderAccounts() {
        UserRepository.RoleCountView teachers = roleCount(Role.TEACHER, 7);
        UserRepository.RoleCountView prekinder = roleCount(Role.PREKINDER_PROFESSIONAL, 3);
        when(users.countByRoleNot(Role.PREKINDER_PROFESSIONAL)).thenReturn(7L);
        when(users.countByActiveTrueAndRoleNot(Role.PREKINDER_PROFESSIONAL)).thenReturn(6L);
        when(users.countByRole()).thenReturn(List.of(teachers, prekinder));

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) service.stats().get("data");

        assertThat(stats.get("total")).isEqualTo(7L);
        assertThat(stats.get("active")).isEqualTo(6L);
        assertThat(stats.get("inactive")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> byRole = (Map<String, Object>) stats.get("byRole");
        assertThat(byRole).containsEntry(Role.TEACHER.name(), 7L);
        assertThat(byRole).doesNotContainKey(Role.PREKINDER_PROFESSIONAL.name());
    }

    private static UserRepository.RoleCountView roleCount(Role role, long total) {
        UserRepository.RoleCountView count = mock(UserRepository.RoleCountView.class);
        when(count.getRole()).thenReturn(role);
        when(count.getTotal()).thenReturn(total);
        return count;
    }
}
