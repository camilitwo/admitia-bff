package cl.mtn.admitiabff.util;

/**
 * Mapea un "flow" lógico (summary, welcome, verification, etc.) al nombre del
 * archivo HTML que vive en {@code src/main/resources/template/}.
 *
 * <p>Convención: el nombre devuelto NO incluye la extensión {@code .html} ni el
 * path; Thymeleaf lo resuelve usando el {@code spring.thymeleaf.prefix} y
 * {@code suffix} configurados en {@code application.yml}.</p>
 *
 * <p>Nunca retorna null. Si el flow es desconocido, devuelve el template base
 * {@code templateCorreo}.</p>
 */
public final class TemplateUtils {

    public static final String DEFAULT_TEMPLATE = "templateCorreo";

    private TemplateUtils() {}

    public static String generateTemplate(String flow) {
        if (flow == null || flow.isBlank()) {
            return DEFAULT_TEMPLATE;
        }
        return switch (flow.trim().toLowerCase()) {
            case "summary"       -> "summaryCorreo";
            case "welcome"       -> "welcomeCorreo";
            case "verification"  -> "verificationCorreo";
            case "interview"     -> "interviewCorreo";
            case "evaluation"    -> "evaluationCorreo";
            default              -> DEFAULT_TEMPLATE;
        };
    }
}
