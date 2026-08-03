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
import org.springframework.web.client.RestClientException;
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
        return send(to, subject, body, null);
    }

    public String send(String to, String subject, String body, String idempotencyKey) {
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
            RestClient.RequestBodySpec request = restClient.post()
                    .uri("/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            Map<String, Object> response = request
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            Object rawMessageId = response == null ? null : response.get("id");
            String messageId = rawMessageId == null ? null : String.valueOf(rawMessageId);
            if (messageId == null || messageId.isBlank() || "null".equalsIgnoreCase(messageId)) {
                throw new ResendDeliveryException(
                        "Resend respondió sin identificador de entrega",
                        true,
                        true,
                        null,
                        null);
            }
            log.info("Resend confirmó el envío messageId={}", messageId);
            return messageId;
        } catch (RestClientResponseException ex) {
            log.error("Resend rechazó el envío con estado HTTP {}", ex.getStatusCode());
            int status = ex.getStatusCode().value();
            String providerError = ex.getResponseBodyAsString();
            boolean concurrentIdempotentRequest = status == 409
                    && providerError != null
                    && providerError.contains("concurrent_idempotent_requests");
            throw new ResendDeliveryException(
                    "Resend API error: " + status,
                    status == 408 || status == 429 || status >= 500 || concurrentIdempotentRequest,
                    false,
                    status,
                    ex);
        } catch (RestClientException ex) {
            log.error("Error de red comunicando con Resend");
            throw new ResendDeliveryException("Error de red comunicando con Resend", true, true, null, ex);
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && from != null && !from.isBlank();
    }

    public static final class ResendDeliveryException extends RuntimeException {
        private final boolean retryable;
        private final boolean deliveryUnknown;
        private final Integer httpStatus;

        public ResendDeliveryException(String message, boolean retryable, boolean deliveryUnknown,
                                       Integer httpStatus, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
            this.deliveryUnknown = deliveryUnknown;
            this.httpStatus = httpStatus;
        }

        public boolean isRetryable() { return retryable; }
        public boolean isDeliveryUnknown() { return deliveryUnknown; }
        public Integer getHttpStatus() { return httpStatus; }
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
