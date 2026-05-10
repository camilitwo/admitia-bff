package cl.mtn.admitiabff.util;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Devuelve el FRAGMENTO HTML (body) que reemplaza el placeholder
 * {@code {{html_replace}}} dentro del layout base
 * {@code src/main/resources/template/templateCorreo.html}.
 *
 * <p>Soporta:
 * <ul>
 *   <li>Interpolación simple {@code {{variable}}}.</li>
 *   <li>Render de listas con {@code {{#each clave}} ... {{/each}}} usando
 *       {@code {{campo}}} dentro del bloque para cada item.</li>
 * </ul>
 * Nunca retorna null.
 */
public final class TemplateUtils {

    private TemplateUtils() {}

    public static String generateTemplate(String flow) {
        return generateTemplate(flow, Map.of());
    }

    public static String generateTemplate(String flow, Map<String, Object> data) {
        String body = bodyFor(flow);
        return render(body, data == null ? Map.of() : data);
    }

    // ------------------------------------------------------------------
    // Plantillas por flow
    // ------------------------------------------------------------------
    private static String bodyFor(String flow) {
        String key = flow == null ? "" : flow.trim().toLowerCase();
        return switch (key) {
            case "summary"      -> SUMMARY_BODY;
            case "welcome"      -> WELCOME_BODY;
            case "verification" -> VERIFICATION_BODY;
            case "interview"    -> INTERVIEW_BODY;
            case "evaluation"   -> EVALUATION_BODY;
            default             -> GENERIC_BODY;
        };
    }

    private static final String SUMMARY_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.6;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Resumen de tus entrevistas</h2>

                <p style="margin:0 0 12px 0;">Estimado/a apoderado/a <strong>{{parentNames}}</strong>,</p>

                <p style="margin:0 0 20px 0;">
                  Te confirmamos el detalle de las entrevistas agendadas para
                  el postulante <strong>{{studentName}}</strong>
                  (curso: <strong>{{gradeApplied}}</strong>) en el proceso de
                  admisión del Colegio Monte Tabor y Nazaret.
                </p>

                <div class="info-box" style="background:#f8f9fa;padding:15px 20px;border-radius:4px;margin-bottom:25px;border-left:4px solid #ff9e18;">
                  <strong>ID Postulación:</strong> {{applicationId}}<br/>
                  <strong>Total de entrevistas:</strong> {{totalInterviews}}
                </div>

                <h3 style="color:#273b7a;margin:0 0 12px 0;font-size:17px;">Detalle de entrevistas</h3>

                <table width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="border-collapse:collapse;margin-bottom:20px;font-size:14px;">
                  <thead>
                    <tr style="background:#273b7a;color:#fff;">
                      <th align="left" style="padding:10px;">Tipo</th>
                      <th align="left" style="padding:10px;">Fecha</th>
                      <th align="left" style="padding:10px;">Hora</th>
                      <th align="left" style="padding:10px;">Modalidad</th>
                      <th align="left" style="padding:10px;">Lugar</th>
                      <th align="left" style="padding:10px;">Entrevistador(es)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {{#each interviews}}
                    <tr style="border-bottom:1px solid #e5e7eb;">
                      <td style="padding:10px;"><strong>{{interviewType}}</strong></td>
                      <td style="padding:10px;">{{scheduledDate}}</td>
                      <td style="padding:10px;">{{scheduledTime}}</td>
                      <td style="padding:10px;">{{mode}}</td>
                      <td style="padding:10px;">{{location}}</td>
                      <td style="padding:10px;">{{interviewerName}}{{secondInterviewerSuffix}}</td>
                    </tr>
                    {{/each}}
                  </tbody>
                </table>

                <p style="margin:20px 0 8px 0;">
                  Te pedimos llegar <strong>10 minutos antes</strong> de cada
                  entrevista. Si necesitas reagendar, responde a este correo
                  o escríbenos a <a href="mailto:admision@mtn.cl">admision@mtn.cl</a>.
                </p>

                <p style="margin:24px 0 0 0;">
                  Saludos cordiales,<br/>
                  <strong>Equipo de Admisión MTN</strong>
                </p>
              </td>
            </tr>
            """;

    private static final String WELCOME_BODY = """
            <tr><td class="content" style="padding:40px 30px;">
              <h2 style="color:#273b7a;margin:0 0 16px 0;">¡Bienvenido/a a MTN!</h2>
              <p>Hola <strong>{{name}}</strong>, hemos recibido tu postulación correctamente.</p>
            </td></tr>
            """;

    private static final String VERIFICATION_BODY = """
            <tr><td class="content" style="padding:40px 30px;">
              <h2 style="color:#273b7a;margin:0 0 16px 0;">Verificación de correo</h2>
              <p>Tu código de verificación es:</p>
              <div class="info-box" style="background:#f8f9fa;padding:15px 20px;border-left:4px solid #ff9e18;">
                <strong style="font-size:20px;">{{code}}</strong>
              </div>
            </td></tr>
            """;

    private static final String INTERVIEW_BODY = """
            <tr><td class="content" style="padding:40px 30px;">
              <h2 style="color:#273b7a;margin:0 0 16px 0;">Entrevista programada</h2>
              <p>Se ha agendado tu entrevista para el <strong>{{date}}</strong> a las <strong>{{time}}</strong>.</p>
            </td></tr>
            """;

    private static final String EVALUATION_BODY = """
            <tr><td class="content" style="padding:40px 30px;">
              <h2 style="color:#273b7a;margin:0 0 16px 0;">Evaluación</h2>
              <p>{{message}}</p>
            </td></tr>
            """;

    private static final String GENERIC_BODY = """
            <tr><td class="content" style="padding:40px 30px;">
              <h2 style="color:#273b7a;margin:0 0 16px 0;">Notificación MTN</h2>
              <p>{{message}}</p>
            </td></tr>
            """;

    // ------------------------------------------------------------------
    // Render con soporte de bloques {{#each ...}}{{/each}} e interpolación.
    // ------------------------------------------------------------------
    private static String render(String template, Map<String, Object> data) {
        if (template == null) return "";
        String out = expandEachBlocks(template, data);
        out = interpolate(out, data);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String expandEachBlocks(String template, Map<String, Object> data) {
        StringBuilder result = new StringBuilder(template.length());
        int cursor = 0;
        while (true) {
            int openStart = template.indexOf("{{#each ", cursor);
            if (openStart < 0) {
                result.append(template, cursor, template.length());
                break;
            }
            int openEnd = template.indexOf("}}", openStart);
            if (openEnd < 0) {
                result.append(template, cursor, template.length());
                break;
            }
            String key = template.substring(openStart + "{{#each ".length(), openEnd).trim();
            int blockStart = openEnd + 2;
            String closeTag = "{{/each}}";
            int blockEnd = template.indexOf(closeTag, blockStart);
            if (blockEnd < 0) {
                result.append(template, cursor, template.length());
                break;
            }
            String inner = template.substring(blockStart, blockEnd);

            // Append todo lo previo al bloque
            result.append(template, cursor, openStart);

            Object listObj = data.get(key);
            Collection<?> items = listObj instanceof Collection<?> c ? c : List.of();
            for (Object item : items) {
                Map<String, Object> itemMap = item instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : Map.of("value", item);
                result.append(interpolate(inner, itemMap));
            }
            cursor = blockEnd + closeTag.length();
        }
        return result.toString();
    }

    private static String interpolate(String template, Map<String, Object> data) {
        if (template == null) return "";
        if (data == null || data.isEmpty()) return template;
        String out = template;
        for (Map.Entry<String, Object> e : data.entrySet()) {
            String token = "{{" + e.getKey() + "}}";
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            out = out.replace(token, value);
        }
        return out;
    }
}
