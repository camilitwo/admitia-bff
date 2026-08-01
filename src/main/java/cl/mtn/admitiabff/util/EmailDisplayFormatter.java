package cl.mtn.admitiabff.util;

import java.util.Locale;

/** Formatea códigos internos para mostrarlos en correos enviados a usuarios. */
public final class EmailDisplayFormatter {

    private EmailDisplayFormatter() {}

    public static String grade(String grade) {
        if (grade == null || grade.isBlank()) return "";

        String normalized = normalizeCode(grade);
        String canonical = normalized
                .replace("Á", "A")
                .replace("Í", "I")
                .replace("º", "")
                .replace("°", "");
        if (canonical.matches("\\d+_BASICO")) {
            return canonical.substring(0, canonical.indexOf('_')) + " Básico";
        }
        if (canonical.matches("\\d+_MEDIO")) {
            return canonical.substring(0, canonical.indexOf('_')) + " Medio";
        }
        return switch (canonical) {
            case "PRE_KINDER", "PREKINDER" -> "Prekínder";
            case "KINDER" -> "Kínder";
            case "I_MEDIO" -> "1 Medio";
            case "II_MEDIO" -> "2 Medio";
            case "III_MEDIO" -> "3 Medio";
            case "IV_MEDIO" -> "4 Medio";
            default -> humanize(canonical);
        };
    }

    public static String interviewType(String interviewType) {
        if (interviewType == null || interviewType.isBlank()) return "";

        String normalized = normalizeCode(interviewType);
        return switch (normalized) {
            case "FAMILY" -> "Familia";
            case "CYCLE_DIRECTOR" -> "Director de ciclo";
            case "PSYCHOLOGIST" -> "Psicólogo/a";
            case "ACADEMIC" -> "Académica";
            case "DIRECTOR" -> "Dirección";
            default -> humanize(normalized);
        };
    }

    public static String mode(String mode) {
        if (mode == null || mode.isBlank()) return "";

        String normalized = normalizeCode(mode);
        return switch (normalized) {
            case "IN_PERSON" -> "Presencial";
            case "ONLINE" -> "Online";
            case "HYBRID" -> "Híbrida";
            default -> humanize(normalized);
        };
    }

    private static String normalizeCode(String value) {
        return value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .replaceAll("_+", "_")
                .toUpperCase(Locale.ROOT);
    }

    private static String humanize(String code) {
        String text = code.replace('_', ' ').toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return text;
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
