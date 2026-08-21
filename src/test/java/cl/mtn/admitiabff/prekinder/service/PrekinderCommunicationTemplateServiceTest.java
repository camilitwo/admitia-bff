package cl.mtn.admitiabff.prekinder.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
