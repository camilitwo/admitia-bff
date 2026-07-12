package cl.mtn.admitiabff.service.payments;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.PaymentStatus;
import cl.mtn.admitiabff.domain.payment.PaymentEntity;
import cl.mtn.admitiabff.domain.payment.PaymentEventEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.PaymentEventRepository;
import cl.mtn.admitiabff.repository.PaymentRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.util.JsonSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentService {
    private static final String PROVIDER = "TOKU";
    private static final List<PaymentStatus> ACTIVE_STATUSES = List.of(PaymentStatus.PAYMENT_PENDING);

    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final TokuClient tokuClient;
    private final TokuProperties properties;
    private final TokuSignatureVerifier signatureVerifier;
    private final JsonSupport jsonSupport;
    private final ObjectMapper objectMapper;

    public PaymentService(ApplicationRepository applicationRepository, UserRepository userRepository, PaymentRepository paymentRepository,
                          PaymentEventRepository paymentEventRepository, TokuClient tokuClient, TokuProperties properties,
                          TokuSignatureVerifier signatureVerifier, JsonSupport jsonSupport, ObjectMapper objectMapper) {
        this.applicationRepository = applicationRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.tokuClient = tokuClient;
        this.properties = properties;
        this.signatureVerifier = signatureVerifier;
        this.jsonSupport = jsonSupport;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> checkout(Long applicationId, Long userId) {
        ApplicationEntity application = loadOwnedApplication(applicationId, userId);
        if (!application.isPaymentRequired() || application.getPaymentStatus() == PaymentStatus.PAID) {
            return Map.of("success", true, "data", statusResponse(application, null));
        }
        BigDecimal amount = properties.applicationFeeClp() == null ? BigDecimal.ZERO : properties.applicationFeeClp();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("APP_PAYMENTS_APPLICATION_FEE_CLP debe ser mayor a 0");
        }

        PaymentEntity payment = paymentRepository.findFirstByApplicationIdAndStatusInOrderByCreatedAtDesc(applicationId, ACTIVE_STATUSES)
            .orElseGet(() -> paymentRepository.findByIdempotencyKey(idempotencyKey(applicationId, userId)).orElse(null));
        if (payment != null && payment.getStatus() == PaymentStatus.PAYMENT_PENDING && payment.getCheckoutUrl() != null && !payment.getCheckoutUrl().isBlank()) {
            return Map.of("success", true, "data", statusResponse(application, payment));
        }
        if (payment == null) {
            payment = new PaymentEntity();
            payment.setApplication(application);
            payment.setGuardianUser(userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado")));
            payment.setProvider(PROVIDER);
            payment.setIdempotencyKey(idempotencyKey(applicationId, userId));
            payment.setAmount(amount);
            payment.setCurrency("CLP");
            payment.setStatus(PaymentStatus.PAYMENT_PENDING);
            payment.setExpiresAt(LocalDateTime.now().plusDays(properties.invoiceDueDays()));
            payment = paymentRepository.save(payment);
        } else {
            payment.setAmount(amount);
            payment.setCurrency("CLP");
            payment.setStatus(PaymentStatus.PAYMENT_PENDING);
            payment.setCheckoutUrl(null);
            payment.setProviderInvoiceId(null);
            payment.setProviderTransactionId(null);
            payment.setPaidAt(null);
            payment.setExpiresAt(LocalDateTime.now().plusDays(properties.invoiceDueDays()));
            payment = paymentRepository.save(payment);
        }

        application.setPaymentStatus(PaymentStatus.PAYMENT_PENDING);
        applicationRepository.save(application);
        audit(payment, "checkout.requested", Map.of("applicationId", applicationId, "amount", amount, "currency", "CLP"));

        try {
            String customerId = ensureTokuCustomer(payment);
            String externalId = payment.getIdempotencyKey();
            Map<String, Object> invoice = tokuClient.createInvoice(
                customerId,
                productId(application),
                LocalDate.now().plusDays(properties.invoiceDueDays()),
                amount,
                "CLP",
                externalId,
                metadata(application, payment),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(properties.invoiceDueDays())
            );
            payment.setProviderCustomerId(customerId);
            payment.setProviderInvoiceId(stringValue(invoice.get("id")));
            payment.setCheckoutUrl(firstNonBlank(invoice.get("link_payment"), invoice.get("linkPayment")));
            if (payment.getCheckoutUrl() == null || payment.getCheckoutUrl().isBlank()) {
                throw new IllegalStateException("Toku no retornó link_payment para la invoice");
            }
            paymentRepository.save(payment);
            audit(payment, "checkout.created", invoice);
            return Map.of("success", true, "data", statusResponse(application, payment));
        } catch (RuntimeException ex) {
            payment.setStatus(PaymentStatus.FAILED);
            application.setPaymentStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            applicationRepository.save(application);
            audit(payment, "checkout.failed", Map.of("message", ex.getMessage()));
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(Long applicationId, Long userId) {
        ApplicationEntity application = loadOwnedApplication(applicationId, userId);
        PaymentEntity payment = paymentRepository.findFirstByApplicationIdAndStatusInOrderByCreatedAtDesc(applicationId, ACTIVE_STATUSES)
            .orElse(null);
        return Map.of("success", true, "data", statusResponse(application, payment));
    }

    @Transactional
    public Map<String, Object> tokuWebhook(String signatureHeader, String rawBody) {
        Map<String, Object> payload = readMap(rawBody);
        String eventId = stringValue(payload.get("id"));
        if (!signatureVerifier.isValid(signatureHeader, eventId, properties.toku().webhookSecret(), properties.webhookToleranceSeconds())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Firma Toku inválida");
        }
        if (paymentEventRepository.existsByProviderAndProviderEventId(PROVIDER, eventId)) {
            return Map.of("success", true, "duplicate", true);
        }

        String eventType = stringValue(payload.get("event_type"));
        Map<String, Object> invoice = nestedMap(payload, "invoice");
        Map<String, Object> paymentIntent = nestedMap(payload, "payment_intent");
        String invoiceId = firstNonBlank(invoice.get("id"), paymentIntent.get("invoice"), paymentIntent.get("invoice_id"));
        PaymentEntity payment = invoiceId == null ? null : paymentRepository.findFirstByProviderInvoiceIdOrderByCreatedAtDesc(invoiceId).orElse(null);

        PaymentEventEntity event = new PaymentEventEntity();
        event.setProvider(PROVIDER);
        event.setProviderEventId(eventId);
        event.setEventType(eventType.isBlank() ? "unknown" : eventType);
        event.setPayload(rawBody == null || rawBody.isBlank() ? "{}" : rawBody);
        event.setPayment(payment);
        paymentEventRepository.save(event);

        if (payment == null) {
            return Map.of("success", true, "matched", false);
        }

        if (isPaid(eventType, invoice, paymentIntent)) {
            markPaid(payment, firstNonBlank(paymentIntent.get("id"), payload.get("payment"), invoice.get("id")));
        } else if (isFailed(eventType, invoice, paymentIntent)) {
            markFailed(payment);
        }
        return Map.of("success", true, "matched", true);
    }

    private ApplicationEntity loadOwnedApplication(Long applicationId, Long userId) {
        ApplicationEntity application = applicationRepository.findActiveById(applicationId)
            .orElseThrow(() -> new IllegalArgumentException("Postulación no encontrada"));
        if (application.getApplicantUser() == null || !userId.equals(application.getApplicantUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puede operar sobre esta postulación");
        }
        return application;
    }

    private String ensureTokuCustomer(PaymentEntity payment) {
        if (payment.getProviderCustomerId() != null && !payment.getProviderCustomerId().isBlank()) {
            return payment.getProviderCustomerId();
        }
        UserEntity user = payment.getGuardianUser();
        String existing = paymentRepository.findFirstByGuardianUserIdAndProviderCustomerIdIsNotNullOrderByCreatedAtDesc(user.getId())
            .map(PaymentEntity::getProviderCustomerId)
            .orElse(null);
        if (existing != null && !existing.isBlank()) return existing;

        Map<String, Object> customer = tokuClient.createCustomer(
            sanitizeRut(user.getRut()),
            user.getEmail(),
            (stringValue(user.getFirstName()) + " " + stringValue(user.getLastName())).trim(),
            user.getPhone(),
            "admitia-user-" + user.getId()
        );
        String customerId = stringValue(customer.get("id"));
        if (customerId.isBlank()) {
            throw new IllegalStateException("Toku no retornó id de customer");
        }
        audit(payment, "customer.created", customer);
        return customerId;
    }

    private void markPaid(PaymentEntity payment, String transactionId) {
        payment.setStatus(PaymentStatus.PAID);
        payment.setProviderTransactionId(transactionId);
        payment.setPaidAt(LocalDateTime.now());
        paymentRepository.save(payment);
        ApplicationEntity application = payment.getApplication();
        application.setPaymentStatus(PaymentStatus.PAID);
        application.setPaidAt(payment.getPaidAt());
        applicationRepository.save(application);
        audit(payment, "payment.paid", Map.of("transactionId", transactionId == null ? "" : transactionId));
    }

    private void markFailed(PaymentEntity payment) {
        if (payment.getStatus() == PaymentStatus.PAID) return;
        payment.setStatus(PaymentStatus.FAILED);
        paymentRepository.save(payment);
        ApplicationEntity application = payment.getApplication();
        application.setPaymentStatus(PaymentStatus.FAILED);
        applicationRepository.save(application);
        audit(payment, "payment.failed", Map.of("providerInvoiceId", stringValue(payment.getProviderInvoiceId())));
    }

    private Map<String, Object> statusResponse(ApplicationEntity application, PaymentEntity payment) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", application.getId());
        data.put("paymentRequired", application.isPaymentRequired());
        data.put("paymentStatus", application.getPaymentStatus().name());
        data.put("paidAt", application.getPaidAt());
        data.put("canFillComplementaryForm", !application.isPaymentRequired() || application.getPaymentStatus() == PaymentStatus.PAID);
        if (payment != null) {
            data.put("paymentId", payment.getId());
            data.put("checkoutUrl", payment.getCheckoutUrl());
            data.put("amount", payment.getAmount());
            data.put("currency", payment.getCurrency());
            data.put("expiresAt", payment.getExpiresAt());
            data.put("providerInvoiceId", payment.getProviderInvoiceId());
        }
        return data;
    }

    private void audit(PaymentEntity payment, String eventType, Object payload) {
        PaymentEventEntity event = new PaymentEventEntity();
        event.setPayment(payment);
        event.setProvider(PROVIDER);
        event.setEventType(eventType);
        event.setPayload(jsonSupport.write(payload));
        paymentEventRepository.save(event);
    }

    private String idempotencyKey(Long applicationId, Long userId) {
        return properties.processId() + "-application-" + applicationId + "-guardian-" + userId;
    }

    private String productId(ApplicationEntity application) {
        return "admision-" + properties.processId() + "-app-" + application.getId();
    }

    private Map<String, Object> metadata(ApplicationEntity application, PaymentEntity payment) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("application_id", String.valueOf(application.getId()));
        metadata.put("guardian_user_id", String.valueOf(payment.getGuardianUser().getId()));
        metadata.put("process_id", properties.processId());
        metadata.put("student_rut", stringValue(application.getStudent().getRut()));
        return metadata;
    }

    private boolean isPaid(String eventType, Map<String, Object> invoice, Map<String, Object> paymentIntent) {
        String invoiceStatus = stringValue(invoice.get("status")).toUpperCase();
        String intentStatus = stringValue(paymentIntent.get("status")).toUpperCase();
        return "invoice.paid".equals(eventType)
            || "payment_intent.succeeded".equals(eventType)
            || Boolean.TRUE.equals(invoice.get("is_paid"))
            || "PAID".equals(invoiceStatus)
            || "SUCCESS".equals(intentStatus)
            || "SUCCEEDED".equals(intentStatus);
    }

    private boolean isFailed(String eventType, Map<String, Object> invoice, Map<String, Object> paymentIntent) {
        String invoiceStatus = stringValue(invoice.get("status")).toUpperCase();
        String intentStatus = stringValue(paymentIntent.get("status")).toUpperCase();
        return eventType.contains("failed")
            || eventType.contains("voided")
            || "FAILED".equals(invoiceStatus)
            || "VOID".equals(invoiceStatus)
            || "VOIDED".equals(invoiceStatus)
            || "FAILED".equals(intentStatus);
    }

    private Map<String, Object> readMap(String rawBody) {
        try {
            if (rawBody == null || rawBody.isBlank()) return Map.of();
            return objectMapper.readValue(rawBody, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("Webhook Toku inválido");
        }
    }

    private Map<String, Object> nestedMap(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return Map.of();
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) return text;
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String sanitizeRut(String value) {
        String rut = stringValue(value).replace(".", "").replace("-", "").trim();
        return rut.isBlank() ? "admitia" : rut;
    }
}
