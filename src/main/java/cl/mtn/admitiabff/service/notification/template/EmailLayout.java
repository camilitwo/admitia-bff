package cl.mtn.admitiabff.service.notification.template;

import java.util.Map;

/**
 * Helpers compartidos para todos los renderers: layout HTML institucional,
 * lectura segura de campos y escapado básico.
 */
public final class EmailLayout {

    private EmailLayout() {}

    /** Envuelve el contenido en un layout HTML mínimo, responsive, branded MTN. */
    public static String wrap(String title, String contentHtml) {
        return """
                <!DOCTYPE html>
                <html lang="es">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width,initial-scale=1.0" />
                  <title>%s</title>
                </head>
                <body style="margin:0;padding:0;background:#f4f6f8;font-family:Arial,Helvetica,sans-serif;color:#1f2937;">
                  <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f4f6f8;padding:24px 0;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="600" cellspacing="0" cellpadding="0" style="background:#ffffff;border-radius:8px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,0.08);">
                          <tr>
                            <td style="background:#0d3b66;padding:20px 24px;color:#ffffff;font-size:18px;font-weight:bold;">
                              Colegio Monte Tabor &amp; Nazaret
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:24px;font-size:15px;line-height:1.6;">
                              %s
                            </td>
                          </tr>
                          <tr>
                            <td style="background:#f1f3f5;padding:16px 24px;font-size:12px;color:#6b7280;text-align:center;">
                              Este es un mensaje automático del sistema de admisión MTN. Por favor no responder a este correo.
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(escape(title), contentHtml);
    }

    public static String str(Map<String, Object> data, String key, String fallback) {
        Object value = data == null ? null : data.get(key);
        if (value == null) return fallback;
        String s = String.valueOf(value);
        return s.isBlank() ? fallback : s;
    }

    public static String escape(String value) {
        if (value == null) return "";
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public static String paragraph(String text) {
        return "<p style=\"margin:0 0 12px 0;\">" + escape(text) + "</p>";
    }

    public static String heading(String text) {
        return "<h2 style=\"margin:0 0 16px 0;color:#0d3b66;font-size:20px;\">" + escape(text) + "</h2>";
    }

    public static String callout(String html) {
        return "<div style=\"background:#eef2ff;border-left:4px solid #0d3b66;padding:12px 16px;margin:12px 0;border-radius:4px;\">"
                + html + "</div>";
    }
}

