package cl.mtn.admitiabff.service.payments;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TokuClient {
    private final RestClient restClient;
    private final TokuProperties properties;

    public TokuClient(RestClient.Builder builder, TokuProperties properties) {
        this.properties = properties;
        this.restClient = builder.baseUrl(trimTrailingSlash(properties.toku().baseUrl())).build();
    }

    public Map<String, Object> createCustomer(String governmentId, String email, String name, String phone, String externalId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("government_id", governmentId);
        body.put("goverment_id", governmentId);
        body.put("mail", email);
        body.put("name", name);
        body.put("phone_number", phone);
        body.put("external_id", externalId);
        body.put("send_mail", false);
        return post("/customers", body);
    }

    public Map<String, Object> createInvoice(String customerId, String productId, LocalDate dueDate, BigDecimal amount, String currency, String externalId, Map<String, Object> metadata, OffsetDateTime expiration) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer", customerId);
        body.put("product_id", productId);
        body.put("due_date", dueDate.toString());
        body.put("amount", amount);
        body.put("is_paid", false);
        body.put("is_void", false);
        body.put("currency_code", currency);
        body.put("invoice_external_id", externalId);
        body.put("disable_automatic_payment", true);
        body.put("metadata", metadata);
        if (expiration != null) {
            body.put("expiration_date", expiration.toString());
        }
        return post("/invoices", body);
    }

    private Map<String, Object> post(String uri, Map<String, Object> body) {
        if (properties.toku().apiKey() == null || properties.toku().apiKey().isBlank()) {
            throw new IllegalStateException("TOKU_API_KEY no está configurada");
        }
        return restClient.post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .headers(headers -> {
                headers.set("x-api-key", properties.toku().apiKey());
                if (properties.toku().accountKey() != null && !properties.toku().accountKey().isBlank()) {
                    headers.set("x-account-key", properties.toku().accountKey());
                }
            })
            .body(body)
            .retrieve()
            .body(Map.class);
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "https://api.trytoku.com";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
