package cl.mtn.admitiabff.service.payments;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.payments")
public record TokuProperties(
    BigDecimal applicationFeeClp,
    String processId,
    int invoiceDueDays,
    long webhookToleranceSeconds,
    Toku toku
) {
    public record Toku(String baseUrl, String apiKey, String accountKey, String webhookSecret) {
    }
}
