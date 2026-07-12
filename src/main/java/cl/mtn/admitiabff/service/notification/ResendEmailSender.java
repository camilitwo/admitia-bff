package cl.mtn.admitiabff.service.notification;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cliente para enviar correos a través de la API REST de Resend (https://resend.com).
 * Documentación: https://resend.com/docs/api-reference/emails/send-email
 *
 * <p>El emisor ({@code from}) <b>siempre</b> se obtiene desde la configuración
 * ({@code app.email.from} / variable {@code APP_EMAIL_FROM}). No hay valor
 * por defecto: si la propiedad no está seteada se lanza {@link IllegalStateException}
 * en el primer envío para evitar correos enviados con un remitente incorrecto.
 */
@Component
public class ResendEmailSender {
    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);
    private static final String DEFAULT_BASE_URL = "https://api.resend.com";

    private final RestClient restClient;
    private final String apiKey;
    private final String from;

    public ResendEmailSender(@Value("${app.email.resend.api-key:}") String apiKey,
                             @Value("${app.email.resend.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
                             @Value("${app.email.from:}") String from) {
        this.apiKey = apiKey;
        this.from = from;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String send(String to, String subject, String body) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Resend API key no configurada. Define app.email.resend.api-key (RESEND_API_KEY).");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "Email remitente no configurado. Define app.email.from (APP_EMAIL_FROM) en el entorno.");
        }
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("Destinatario (to) requerido para enviar email.");
        }
        String safeBody = body == null ? "" : body;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);
        payload.put("to", new String[]{to});
        payload.put("subject", subject);
        // Resend acepta html y/o text. Detectamos si el body trae HTML real;
        // si no, lo mandamos como texto plano para que se renderice correctamente.
        if (looksLikeHtml(safeBody)) {
            payload.put("html", safeBody);
            payload.put("text", stripHtml(safeBody));
        } else {
            payload.put("text", safeBody);
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            String messageId = response == null ? null : String.valueOf(response.get("id"));
            log.info("Resend email enviado from={} to={} messageId={}", from, to, messageId);
            return messageId;
        } catch (RestClientResponseException ex) {
            log.error("Error Resend ({}) enviando from={} to={}: {}", ex.getStatusCode(), from, to, ex.getResponseBodyAsString());
            throw new RuntimeException("Resend API error: " + ex.getStatusCode() + " - " + ex.getResponseBodyAsString(), ex);
        }
    }

    /**
     * Heurística simple: el body se considera HTML si contiene al menos un tag
     * (apertura {@code <tag} seguida de {@code >}). Suficiente para distinguir
     * los templates HTML que arma {@code EmailLayout} de mensajes en texto plano.
     */
    private boolean looksLikeHtml(String body) {
        if (body == null || body.isBlank()) return false;
        // Busca un tag HTML/XML válido. No es un parser, basta para clasificar.
        return body.matches("(?is).*<\\s*[a-zA-Z!][^>]*>.*");
    }

    private String stripHtml(String body) {
        return body == null ? "" : body.replaceAll("<[^>]+>", "");
    }
}

