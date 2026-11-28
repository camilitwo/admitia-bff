package cl.mtn.admitiabff.prekinder.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrekinderRubricServiceTest {
    @Test
    void acceptsConditionalPrekinderInstruments() throws Exception {
        assertEquals("LEARNING_SUPPORT", validateInstrument("LEARNING_SUPPORT"));
        assertEquals("DAP", validateInstrument("DAP"));
    }

    @Test
    void rejectsUnknownPrekinderInstrument() throws Exception {
        var error = assertThrows(InvocationTargetException.class,
            () -> validateInstrument("UNKNOWN_INSTRUMENT"));

        assertEquals("Instrumento de pauta inválido", error.getCause().getMessage());
    }

    @Test
    void acceptsRealEditableRubricContent() {
        var command = draft(List.of(
            criterion("LENGUAJE", "Comprensión oral", option("0", "Aún no"), option("2.5", "Consolidado")),
            criterion("MOTRICIDAD", "Coordinación", option("0", "Aún no"), option("3", "Consolidado"))));

        assertDoesNotThrow(() -> PrekinderRubricService.validateDraft(command));
        assertEquals("PSYCHOMOTOR", command.instrumentCode());
        assertEquals("Describe cambios reales", command.criteria().getFirst().descriptor());
    }

    @Test
    void rejectsDuplicatedCriterionCodes() {
        var command = draft(List.of(
            criterion("LENGUAJE", "Uno", option("0", "Aún no"), option("1", "Logrado")),
            criterion("lenguaje", "Dos", option("0", "Aún no"), option("2", "Logrado"))));

        var error = assertThrows(IllegalArgumentException.class,
            () -> PrekinderRubricService.validateDraft(command));

        assertEquals("Los códigos de criterio no pueden repetirse", error.getMessage());
    }

    @Test
    void rejectsScoresThatAreNotStrictlyAscending() {
        var command = draft(List.of(criterion("LENGUAJE", "Comprensión",
            option("2", "Logrado"), option("1", "Inicial"))));

        var error = assertThrows(IllegalArgumentException.class,
            () -> PrekinderRubricService.validateDraft(command));

        assertEquals("Ordena las opciones por puntaje ascendente sin duplicados", error.getMessage());
    }

    private static PrekinderRubricService.DraftCommand draft(
        List<PrekinderRubricService.CriterionCommand> criteria) {
        return new PrekinderRubricService.DraftCommand(
            "Pauta psicomotriz revisada", "PSYCHOMOTOR", 4, criteria);
    }

    private static PrekinderRubricService.CriterionCommand criterion(String code, String name,
        PrekinderRubricService.OptionCommand... options) {
        return new PrekinderRubricService.CriterionCommand(
            code, name, "Describe cambios reales", true, List.of(options));
    }

    private static PrekinderRubricService.OptionCommand option(String value, String label) {
        return new PrekinderRubricService.OptionCommand(
            new BigDecimal(value), label, "Descriptor observable", false);
    }

    private static String validateInstrument(String value) throws Exception {
        Method method = PrekinderRubricService.class.getDeclaredMethod("instrument", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }
}
