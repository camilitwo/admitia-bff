package cl.mtn.admitiabff.prekinder.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PrekinderCommunicationTemplateServiceTest {
    @Test
    void rendersOnlyTheSupportedProcessVariables() {
        String rendered = PrekinderCommunicationTemplateService.render(
            "Hola {{applicantName}} · {{processName}} · {{portalUrl}} · {{deadline}}",
            "Ana Pérez", "Admisión 2027", "https://portal.example", "30-09-2026");

        assertEquals("Hola Ana Pérez · Admisión 2027 · https://portal.example · 30-09-2026", rendered);
        assertFalse(rendered.contains("{{"));
    }

    @Test
    void preservesOrdinaryContentAroundVariables() {
        assertEquals("<p>Resultado de Matías disponible.</p>",
            PrekinderCommunicationTemplateService.render(
                "<p>Resultado de {{applicantName}} disponible.</p>",
                "Matías", "Proceso", "https://portal.example", ""));
    }

    @Test
    void rendersCompleteScheduleVariablesAndRemovesOptionalImage() {
        String rendered = PrekinderCommunicationTemplateService.render(
            "{{institutionalImage}} {{scheduleDate}} {{startTime}} {{endTime}} {{modality}} "
                + "{{location}} {{groupCode}} {{evaluationDetail}} {{reason}} {{institutionalImageUrl}}",
            Map.of("scheduleDate", "10 de agosto", "startTime", "08:30", "endTime", "09:00",
                "modality", "Presencial", "location", "Sala Arrayán", "groupCode", "M3-04",
                "evaluationDetail", "Evaluación académica", "reason", "Cambio de jornada"));

        assertEquals(" 10 de agosto 08:30 09:00 Presencial Sala Arrayán M3-04 Evaluación académica Cambio de jornada ", rendered);
        assertFalse(rendered.contains("{{"));
    }
}
