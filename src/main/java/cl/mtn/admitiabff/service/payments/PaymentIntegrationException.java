package cl.mtn.admitiabff.service.payments;

import org.springframework.http.HttpStatus;

public class PaymentIntegrationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public PaymentIntegrationException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static PaymentIntegrationException invalidData(String message) {
        return new PaymentIntegrationException(HttpStatus.BAD_REQUEST, "PAYMENT_DATA_INVALID", message);
    }

    public static PaymentIntegrationException schoolValidation(String message) {
        return new PaymentIntegrationException(HttpStatus.UNPROCESSABLE_ENTITY, "SCHOOL_VALIDATION_ERROR", message);
    }

    public static PaymentIntegrationException auth(String message) {
        return new PaymentIntegrationException(HttpStatus.BAD_GATEWAY, "SCHOOL_AUTH_ERROR", message);
    }

    public static PaymentIntegrationException missingLink() {
        return new PaymentIntegrationException(HttpStatus.BAD_GATEWAY, "SCHOOL_PAYMENT_LINK_MISSING", "El colegio creó el cobro pero no entregó un enlace de pago");
    }

    public static PaymentIntegrationException unavailable(String message) {
        return new PaymentIntegrationException(HttpStatus.SERVICE_UNAVAILABLE, "SCHOOL_API_UNAVAILABLE", message);
    }
}
