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
                             @Value("${app.email.from:no-reply@mtn.cl}") String from) {
        this.apiKey = apiKey;
        this.from = from;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String send(String to, String subject, String body) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Resend API key no configurada. Define app.email.resend.api-key (RESEND_API_KEY).");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);
        payload.put("to", new String[]{to});
        payload.put("subject", subject);
        payload.put("html", body);
        payload.put("text", stripHtml(body));

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            String messageId = response == null ? null : String.valueOf(response.get("id"));
            log.info("Resend email enviado a {} messageId={}", to, messageId);
            return messageId;
        } catch (RestClientResponseException ex) {
            log.error("Error Resend ({}) enviando a {}: {}", ex.getStatusCode(), to, ex.getResponseBodyAsString());
            throw new RuntimeException("Resend API error: " + ex.getStatusCode() + " - " + ex.getResponseBodyAsString(), ex);
        }
    }

    private String stripHtml(String body) {
        return body == null ? "" : body.replaceAll("<[^>]+>", "");
    }
}

