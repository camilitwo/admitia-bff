package cl.mtn.admitiabff.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.service.ProfessionalActivityService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class UsersControllerProfessionalActivityTest {

    @Test
    void exposesProfessionalActivityWithProcessAndRoleFilters() {
        var expected = new ProfessionalActivityService.ProfessionalActivityResponse("KIV-2027-02", List.of());
        AtomicReference<String> receivedProcess = new AtomicReference<>();
        AtomicReference<Role> receivedRole = new AtomicReference<>();
        ProfessionalActivityService activityService = new ProfessionalActivityService(null, null, null, "KIV-2027-02") {
            @Override
            public ProfessionalActivityResponse getActivity(String processCode, Role role) {
                receivedProcess.set(processCode);
                receivedRole.set(role);
                return expected;
            }
        };

        UsersController controller = new UsersController(null, null, activityService);

        Map<String, Object> response = controller.professionalActivity("KIV-2027-02", "PSYCHOLOGIST");

        assertEquals(true, response.get("success"));
        assertSame(expected, response.get("data"));
        assertEquals(0, response.get("count"));
        assertEquals("KIV-2027-02", receivedProcess.get());
        assertEquals(Role.PSYCHOLOGIST, receivedRole.get());
    }

    @Test
    void normalizesWhitespaceAndCaseInRoleFilter() {
        AtomicReference<Role> receivedRole = new AtomicReference<>();
        ProfessionalActivityService activityService = new ProfessionalActivityService(null, null, null, "KIV-2027-02") {
            @Override
            public ProfessionalActivityResponse getActivity(String processCode, Role role) {
                receivedRole.set(role);
                return new ProfessionalActivityResponse("KIV-2027-02", List.of());
            }
        };

        UsersController controller = new UsersController(null, null, activityService);

        controller.professionalActivity("KIV-2027-02", " psychologist ");

        assertEquals(Role.PSYCHOLOGIST, receivedRole.get());
    }

    @Test
    void professionalActivityIsRestrictedToAdminAndCoordinator() throws NoSuchMethodException {
        PreAuthorize annotation = UsersController.class
            .getMethod("professionalActivity", String.class, String.class)
            .getAnnotation(PreAuthorize.class);

        assertEquals("hasAnyRole('ADMIN', 'COORDINATOR')", annotation.value());
    }
}
