package cl.mtn.admitiabff.service.payments;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.integrations.mtn-admission")
public record MtnAdmissionProperties(
    boolean enabled,
    String baseUrl,
    String tokenPath,
    String guardiansPath,
    String chargesPath,
    String clientCode,
    String clientSecret,
    ClientAuthMethod clientAuthMethod,
    boolean allowInsecureHttp,
    Duration connectTimeout,
    Duration readTimeout,
    BigDecimal applicationFee,
    String paymentGlosa,
    String currency,
    int dueDays,
    String referencePrefix,
    String defaultCity,
    String providerZone
) {
    public enum ClientAuthMethod { BASIC, FORM }

    public void validateForUse() {
        if (!enabled) throw PaymentIntegrationException.unavailable("La integración de pagos MTN no está habilitada");
        if (isBlank(baseUrl) || isBlank(clientCode) || isBlank(clientSecret)) {
            throw PaymentIntegrationException.auth("La integración MTN no tiene URL o credenciales configuradas");
        }
        if (baseUrl.startsWith("http://") && !allowInsecureHttp) {
            throw PaymentIntegrationException.auth("La API MTN debe utilizar HTTPS fuera de QA");
        }
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            throw PaymentIntegrationException.auth("MTN_ADMISSION_BASE_URL no es una URL HTTP(S) válida");
        }
        if (applicationFee == null || applicationFee.signum() <= 0) {
            throw PaymentIntegrationException.invalidData("El monto configurado para la postulación debe ser mayor a cero");
        }
        if (isBlank(paymentGlosa)) {
            throw PaymentIntegrationException.invalidData("MTN_ADMISSION_PAYMENT_GLOSA debe estar configurada");
        }
        if (!("CLP".equalsIgnoreCase(currency) || "CLF".equalsIgnoreCase(currency))) {
            throw PaymentIntegrationException.invalidData("La moneda configurada debe ser CLP o CLF");
        }
        if (dueDays < 0) throw PaymentIntegrationException.invalidData("Los días de vencimiento no pueden ser negativos");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
