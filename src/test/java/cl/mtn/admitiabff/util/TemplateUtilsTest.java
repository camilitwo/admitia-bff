package cl.mtn.admitiabff.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateUtilsTest {

    @Test
    void evaluationAssignmentOmitsEvaluationType() {
        String html = TemplateUtils.generateTemplate("evaluation_assignment", Map.of(
                "evaluatorName", "Jorge",
                "studentName", "ALONSO GONZALEZ",
                "gradeApplied", "1 Medio",
                "deadline", "2026-08-01T00:00"));

        assertTrue(html.contains("Curso al que postula:</strong> 1 Medio"));
        assertTrue(html.contains("Plazo:</strong> 2026-08-01T00:00"));
        assertFalse(html.contains("Tipo de evaluación:"));
        assertFalse(html.contains("{{evaluationType}}"));
    }

    @Test
    void interviewerConfirmationRendersFriendlyValues() {
        String html = TemplateUtils.generateTemplate("interview_confirmed_for_interviewer", Map.of(
                "interviewerName", "Entrevistador Uno",
                "studentName", "ALONSO GONZALEZ",
                "interviewType", EmailDisplayFormatter.interviewType("FAMILY"),
                "scheduledDate", "2026-08-06",
                "scheduledTime", "12:00",
                "mode", EmailDisplayFormatter.mode("IN_PERSON"),
                "location", "Sala 2"));

        assertTrue(html.contains("Tipo de entrevista:</strong> Familia"));
        assertTrue(html.contains("Modalidad:</strong> Presencial"));
        assertFalse(html.contains("FAMILY"));
        assertFalse(html.contains("IN_PERSON"));
    }

    @Test
    void cycleDirectorConfirmationRendersFriendlyValues() {
        String html = TemplateUtils.generateTemplate("interview_confirmed_for_interviewer", Map.of(
                "interviewerName", "Director Uno",
                "studentName", "ALONSO GONZALEZ",
                "interviewType", EmailDisplayFormatter.interviewType("CYCLE_DIRECTOR"),
                "scheduledDate", "2026-08-06",
                "scheduledTime", "11:00",
                "mode", EmailDisplayFormatter.mode("IN_PERSON"),
                "location", "Sala 2"));

        assertTrue(html.contains("Tipo de entrevista:</strong> Director de ciclo"));
        assertTrue(html.contains("Modalidad:</strong> Presencial"));
        assertFalse(html.contains("CYCLE_DIRECTOR"));
        assertFalse(html.contains("IN_PERSON"));
    }
}
