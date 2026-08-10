package cl.mtn.admitiabff.prekinder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.prekinder.service.PrekinderEvaluatorService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrekinderEvaluatorControllerTest {
    private final PrekinderEvaluatorService evaluators = mock(PrekinderEvaluatorService.class);
    private final PrekinderEvaluatorController controller = new PrekinderEvaluatorController(evaluators);

    @Test
    void exposesTheProfessionalsCompletePrekinderWorkspaceForTheSelectedDay() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        var workspace = new PrekinderEvaluatorService.EvaluatorWorkspace(UUID.randomUUID(), date, 0, List.of());
        when(evaluators.workspace(null, date)).thenReturn(workspace);

        Map<String, Object> response = controller.workspace(null, date);

        verify(evaluators).workspace(null, date);
        assertThat(response.get("success")).isEqualTo(true);
        assertThat(response.get("data")).isEqualTo(workspace);
    }
}
