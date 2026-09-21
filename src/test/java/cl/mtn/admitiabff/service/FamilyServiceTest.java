package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.application.FamilyEntity;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FamilyServiceTest {
    @Mock JdbcTemplate jdbc;
    @Mock ApplicationRepository applications;
    @Mock AuthService auth;
    @Mock ApplicationService applicationService;

    private FamilyService service;

    @BeforeEach
    void setUp() {
        service = new FamilyService(jdbc, applications, auth, applicationService);
        when(auth.requireAuth()).thenReturn(new AuthService.AuthContextHolder(7L, "familia@example.cl", "APODERADO"));
        when(auth.hasAnyRoleContext(any(), any(Role[].class))).thenReturn(false);
    }

    @Test
    void familyFormUsesOneRepresentativeApplicationForTheProcess() {
        ApplicationEntity firstChild = application(11L, 3L);
        ApplicationEntity secondChild = application(12L, 3L);
        when(jdbc.queryForObject(anyString(), any(Class.class), anyLong(), anyLong())).thenReturn(1);
        when(applications.findByFamilyIdAndAcademicYearAndDeletedAtIsNullOrderByCreatedAtAsc(3L, 2027))
            .thenReturn(List.of(firstChild, secondChild));
        when(applicationService.complementaryForm(11L)).thenReturn(Map.of("success", true, "formId", 99L));

        Map<String, Object> response = service.form(3L, 2027);

        assertEquals(99L, response.get("formId"));
        verify(applicationService).complementaryForm(11L);
        verify(applicationService, never()).complementaryForm(12L);
    }

    @Test
    void unrelatedGuardianCannotOpenFamilyForm() {
        when(jdbc.queryForObject(anyString(), any(Class.class), anyLong(), anyLong())).thenReturn(0);

        assertThrows(ResponseStatusException.class, () -> service.form(3L, 2027));
        verify(applicationService, never()).complementaryForm(anyLong());
    }

    private static ApplicationEntity application(long id, long familyId) {
        FamilyEntity family = new FamilyEntity();
        family.setId(familyId);
        ApplicationEntity application = new ApplicationEntity();
        application.setId(id);
        application.setFamily(family);
        return application;
    }
}
