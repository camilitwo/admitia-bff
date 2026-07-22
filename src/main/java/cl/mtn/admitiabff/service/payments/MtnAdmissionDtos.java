package cl.mtn.admitiabff.service.payments;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public final class MtnAdmissionDtos {
    private MtnAdmissionDtos() {}

    public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdmissionRequest(
        String value,
        String valueValidator,
        String name,
        String email,
        String address1,
        String address2,
        String city,
        String postal,
        List<StudentRequest> alumnos
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StudentRequest(String value, String valueValidator, String name, String codCurso) {}

    public record AdmissionResponse(
        Boolean ok,
        String estado,
        String mensaje,
        List<String> errores,
        List<String> advertencias,
        @JsonProperty("c_bpartner_id") Long businessPartnerId,
        @JsonProperty("c_bpartner_location_id") Long businessPartnerLocationId,
        @JsonProperty("apoderado_rut") String guardianRut,
        @JsonProperty("apoderado_estado") String guardianState,
        @JsonProperty("toku_customer_id") String tokuCustomerId,
        @JsonProperty("toku_customer_estado") String tokuCustomerState,
        List<StudentResponse> alumnos
    ) {}

    public record StudentResponse(
        String rut,
        String nombre,
        @JsonProperty("ad_user_id") Long userId,
        String estado,
        @JsonProperty("toku_subscription_id") String tokuSubscriptionId,
        @JsonProperty("toku_subscription_estado") String tokuSubscriptionState,
        String detalle
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChargeRequest(
        @JsonProperty("apoderado_rut") String guardianRut,
        @JsonProperty("apoderado_dv") String guardianDv,
        @JsonProperty("apoderado_nombre") String guardianName,
        @JsonProperty("apoderado_email") String guardianEmail,
        @JsonProperty("alumno_rut") String studentRut,
        @JsonProperty("alumno_dv") String studentDv,
        @JsonProperty("alumno_nombre") String studentName,
        @JsonProperty("alumno_curso") String studentCourse,
        @JsonProperty("monto") BigDecimal amount,
        @JsonProperty("moneda") String currency,
        @JsonProperty("fecha_vencimiento") String dueDate,
        @JsonProperty("concepto") String concept,
        @JsonProperty("referencia_externa") String externalReference
    ) {}

    public record ChargeResponse(
        Boolean ok,
        String estado,
        String mensaje,
        List<String> errores,
        List<String> advertencias,
        @JsonProperty("c_orderpayschedule_id") Long chargeId,
        @JsonProperty("toku_invoice_id") String tokuInvoiceId,
        @JsonProperty("link_pago") String paymentLink,
        @JsonProperty("monto") BigDecimal amount,
        @JsonProperty("moneda") String currency,
        @JsonProperty("fecha_vencimiento") String dueDate,
        @JsonProperty("estado_pago") String paymentStatus,
        @JsonProperty("c_bpartner_id") Long businessPartnerId,
        @JsonProperty("ad_user_id") Long studentUserId,
        @JsonProperty("referencia_externa") String externalReference
    ) {}

    public record ChargeStatusResponse(
        Boolean encontrado,
        @JsonProperty("c_orderpayschedule_id") Long chargeId,
        @JsonProperty("toku_invoice_id") String tokuInvoiceId,
        Boolean pagado,
        String estado,
        @JsonProperty("monto") BigDecimal amount,
        @JsonProperty("moneda") String currency,
        @JsonProperty("fecha_vencimiento") String dueDate,
        @JsonProperty("fecha_pago") String paidAt,
        @JsonProperty("monto_pagado") BigDecimal paidAmount,
        @JsonProperty("toku_transaction_id") String transactionId,
        String voucher,
        @JsonProperty("medio_pago") String paymentMethod,
        @JsonProperty("link_pago") String paymentLink
    ) {}
}
