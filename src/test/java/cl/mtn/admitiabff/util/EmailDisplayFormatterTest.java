package cl.mtn.admitiabff.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EmailDisplayFormatterTest {

    @Test
    void formatsGradesWithoutInternalSeparators() {
        assertEquals("1 Básico", EmailDisplayFormatter.grade("1_basico"));
        assertEquals("1 Básico", EmailDisplayFormatter.grade("1º Básico"));
        assertEquals("4 Medio", EmailDisplayFormatter.grade("4_MEDIO"));
        assertEquals("1 Medio", EmailDisplayFormatter.grade("I_MEDIO"));
        assertEquals("Prekínder", EmailDisplayFormatter.grade("PRE_KINDER"));
    }

    @Test
    void formatsInterviewTypesForRecipients() {
        assertEquals("Familia", EmailDisplayFormatter.interviewType("FAMILY"));
        assertEquals("Director de ciclo", EmailDisplayFormatter.interviewType("CYCLE_DIRECTOR"));
        assertEquals("Other type", EmailDisplayFormatter.interviewType("OTHER_TYPE"));
    }

    @Test
    void formatsInterviewModesForRecipients() {
        assertEquals("Presencial", EmailDisplayFormatter.mode("IN_PERSON"));
        assertEquals("Híbrida", EmailDisplayFormatter.mode("HYBRID"));
        assertEquals("Other mode", EmailDisplayFormatter.mode("OTHER_MODE"));
    }
}
