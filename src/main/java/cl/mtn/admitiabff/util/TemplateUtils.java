package cl.mtn.admitiabff.util;

import java.util.Map;

/**
 * Devuelve el FRAGMENTO HTML (body) que debe reemplazar el placeholder
 * {@code {{html_replace}}} dentro del layout base
 * {@code src/main/resources/template/templateCorreo.html}.
 *
 * <p>Soporta interpolación simple {@code {{variable}}} contra un mapa de datos.
 * Nunca retorna {@code null}: si el flow es desconocido devuelve un body
 * genérico de "Notificación".</p>
 */
public final class TemplateUtils {

    private TemplateUtils() {}

    /** Sobrecarga para callers que no tienen variables que interpolar. */
    public static String generateTemplate(String flow) {
        return generateTemplate(flow, Map.of());
    }

    public static String generateTemplate(String flow, Map<String, Object> data) {
        String body = bodyFor(flow);
        return interpolate(body, data == null ? Map.of() : data);
    }

    private static String bodyFor(String flow) {
        String key = flow == null ? "" : flow.trim().toLowerCase();
        return switch (key) {
            case "summary" -> """
                    <tr><td class="content">
                        <h2 style="color:#273b7a;margin-top:0;">Resumen de tu postulación</h2>
                        <p>Estimado/a apoderado/a,</p>
                        <p>{{summary}}</p>
                        <div class="info-box">
                            <strong>ID Postulación:</strong> {{applicationId}}
                        </div>
                        <p>Saludos cordiales,<br/>Equipo de Admisión MTN.</p>
                    </td></tr>
                    """;
            case "welcome" -> """
                    <tr><td class="content">
                        <h2 style="color:#273b7a;margin-top:0;">¡Bienvenido/a a MTN!</h2>
                        <p>Hola {{name}}, hemos recibido tu postulación correctamente.</p>
                    </td></tr>
                    """;
            case "verification" -> """
                    <tr><td class="content">
                        <h2 style="color:#273b7a;margin-top:0;">Verificación de correo</h2>
                        <p>Tu código de verificación es:</p>
                        <div class="info-box"><strong>{{code}}</strong></div>
                    </td></tr>
                    """;
            case "interview" -> """
                    <tr><td class="content">
                        <h2 style="color:#273b7a;margin-top:0;">Entrevista programada</h2>
                        <p>Se ha agendado tu entrevista para el {{date}} a las {{time}}.</p>
                    </td></tr>
                    """;
            case "evaluation" -> """
                    <tr><td class="content">
                        <h2 style="color:#273b7a;margin-top:0;">Evaluación</h2>
                        <p>{{message}}</p>
                    </td></tr>
                    """;
            default -> """
                    <tr><td class="content">
                        <h2 style="color:#273b7a;margin-top:0;">Notificación MTN</h2>
                        <p>{{message}}</p>
                    </td></tr>
                    """;
        };
    }

    private static String interpolate(String template, Map<String, Object> data) {
        if (template == null) return "";
        if (data.isEmpty()) return template;
        String out = template;
        for (Map.Entry<String, Object> e : data.entrySet()) {
            String token = "{{" + e.getKey() + "}}";
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            out = out.replace(token, value);
        }
        return out;
    }
}
