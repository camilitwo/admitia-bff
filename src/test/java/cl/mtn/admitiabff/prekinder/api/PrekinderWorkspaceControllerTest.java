package cl.mtn.admitiabff.prekinder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.prekinder.service.PrekinderFieldService;
import cl.mtn.admitiabff.prekinder.service.PrekinderWorkspaceService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PrekinderWorkspaceControllerTest {
    private final PrekinderWorkspaceService workspace = mock(PrekinderWorkspaceService.class);
    private final PrekinderWorkspaceController controller =
        new PrekinderWorkspaceController(workspace, mock(PrekinderFieldService.class));

    @Test
    void listsProcessesWithoutUsingTheLegacyModel() {
        var process = new PrekinderWorkspaceService.ProcessView(UUID.randomUUID(), 2027, "Prekínder 2027",
            "DRAFT", null, null, 0, 0, false);
        when(workspace.listProcesses()).thenReturn(List.of(process));

        Map<String, Object> response = controller.processes();

        assertThat(response.get("success")).isEqualTo(true);
        assertThat(response.get("data")).isEqualTo(List.of(process));
    }

    @Test
    void exposesOnlyTheActiveApplicationOptionsToTheGuardianFlow() {
        var option = new PrekinderWorkspaceService.ApplicationOption(
            UUID.randomUUID(), 2027, "Prekínder 2027", UUID.randomUUID(), "NEW_FAMILIES",
            Instant.parse("2026-08-05T12:00:00Z"), Instant.parse("2026-08-31T23:59:00Z"));
        when(workspace.applicationOptions()).thenReturn(List.of(option));

        Map<String, Object> response = controller.applicationOptions();

        verify(workspace).applicationOptions();
        assertThat(response.get("success")).isEqualTo(true);
        assertThat(response.get("data")).isEqualTo(List.of(option));
    }

    @Test
    void publishesAnExplicitApplicationWindow() {
        UUID processId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-08-05T12:00:00Z");
        Instant endsAt = Instant.parse("2026-12-31T23:59:00Z");
        when(workspace.publishProcess(processId, startsAt, endsAt)).thenReturn(
            new PrekinderWorkspaceService.ProcessView(processId, 2027, "Prekínder 2027", "PUBLISHED",
                startsAt, endsAt, 1, 0, true));

        controller.publish(processId, new PrekinderWorkspaceController.PublishProcess(startsAt, endsAt));

        verify(workspace).publishProcess(processId, startsAt, endsAt);
    }

    @Test
    void passesRutAndApplicantIdentityToTheIsolatedWorkspace() {
        UUID processId = UUID.randomUUID();
        var request = new PrekinderWorkspaceController.CreateApplication(
            processId, "12.345.678-5", " Ana ", " Pérez ", " Soto ");
        var expectedIdentity = new PrekinderWorkspaceService.Identity("12.345.678-5", "Ana", "Pérez", "Soto");
        when(workspace.createApplication(org.mockito.ArgumentMatchers.eq(processId),
            org.mockito.ArgumentMatchers.any(PrekinderWorkspaceService.Identity.class))).thenReturn(
                new PrekinderWorkspaceService.ApplicationView(UUID.randomUUID(), UUID.randomUUID(), processId,
                    "DRAFT", expectedIdentity, Instant.now()));

        controller.application(request);

        ArgumentCaptor<PrekinderWorkspaceService.Identity> identity =
            ArgumentCaptor.forClass(PrekinderWorkspaceService.Identity.class);
        verify(workspace).createApplication(org.mockito.ArgumentMatchers.eq(processId), identity.capture());
        assertThat(identity.getValue()).isEqualTo(expectedIdentity);
    }
}
