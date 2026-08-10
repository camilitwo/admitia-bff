package cl.mtn.admitiabff.prekinder.config;

import cl.mtn.admitiabff.service.payments.PaymentIntegrationException;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.prekinder.payment")
public record PrekinderPaymentProperties(
    boolean enabled,
    BigDecimal applicationFee,
    String paymentGlosa,
    String currency,
    int dueDays,
    String referencePrefix
) {
    public void validateForUse() {
        if (!enabled) throw PaymentIntegrationException.unavailable("Los pagos de Prekínder no están habilitados");
        if (applicationFee == null || applicationFee.signum() <= 0) {
            throw PaymentIntegrationException.invalidData("El monto configurado para Prekínder debe ser mayor a cero");
        }
        if (paymentGlosa == null || paymentGlosa.isBlank()) {
            throw PaymentIntegrationException.invalidData("La glosa de pago de Prekínder debe estar configurada");
        }
        if (!("CLP".equalsIgnoreCase(currency) || "CLF".equalsIgnoreCase(currency))) {
            throw PaymentIntegrationException.invalidData("La moneda configurada para Prekínder debe ser CLP o CLF");
        }
        if (dueDays < 0) throw PaymentIntegrationException.invalidData("Los días de vencimiento no pueden ser negativos");
    }
}
