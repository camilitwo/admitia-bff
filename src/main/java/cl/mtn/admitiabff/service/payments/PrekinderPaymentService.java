package cl.mtn.admitiabff.service.payments;

import cl.mtn.admitiabff.prekinder.config.PrekinderPaymentProperties;
import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import cl.mtn.admitiabff.prekinder.crypto.EnvelopeEncryptionService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.service.PrekinderAccessService;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.AdmissionRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.AdmissionResponse;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeResponse;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeStatusResponse;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.StudentRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.StudentResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Pago MTN para una postulación UUID, persistido exclusivamente en la base Prekínder. */
@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderPaymentService {
    private static final String PROVIDER = "MTN_ADMISSION_API";
    private static final Set<String> GUARDIAN_STATES = Set.of("creado", "ya_existia");
    private static final Set<String> CUSTOMER_STATES = Set.of("creado", "ya_existia", "ya_existia_toku");
    private static final Set<String> STUDENT_STATES = Set.of("creado", "ya_existia");
    private static final DateTimeFormatter SCHOOL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final EnvelopeEncryptionService encryption;
    private final ObjectMapper mapper;
    private final MtnAdmissionGateway admissionGateway;
    private final MtnAdmissionProperties admissionProperties;
    private final PrekinderPaymentProperties paymentProperties;
    private final ZoneId providerZone;

    public PrekinderPaymentService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
                                   @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
                                   PrekinderAccessService access,
                                   EnvelopeEncryptionService encryption,
                                   ObjectMapper mapper,
                                   MtnAdmissionGateway admissionGateway,
                                   MtnAdmissionProperties admissionProperties,
                                   PrekinderPaymentProperties paymentProperties) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.encryption = encryption;
        this.mapper = mapper;
        this.admissionGateway = admissionGateway;
        this.admissionProperties = admissionProperties;
        this.paymentProperties = paymentProperties;
        this.providerZone = ZoneId.of(blank(admissionProperties.providerZone()) ? "America/Santiago" : admissionProperties.providerZone());
    }

    public Map<String, Object> checkout(UUID applicationId) {
        paymentProperties.validateForUse();
        admissionProperties.validateConnectionForUse();
        PrekinderActor actor = access.requireActor();
        try {
            return transactions.execute(status -> checkoutInTransaction(loadOwned(applicationId, actor, true), actor));
        } catch (PaymentIntegrationException exception) {
            transactions.executeWithoutResult(status -> markFailed(applicationId, actor.id()));
            throw exception;
        }
    }

    public Map<String, Object> status(UUID applicationId) {
        PrekinderActor actor = access.requireActor();
        return transactions.execute(transaction -> {
            ApplicationData application = loadOwned(applicationId, actor, true);
            PaymentData payment = latestPayment(applicationId);
            if (payment != null && "PAYMENT_PENDING".equals(payment.status()) && payment.chargeId() != null) {
                payment = reconcile(application, payment);
            }
            return wrap(response(applicationId, application, payment));
        });
    }

    private Map<String, Object> checkoutInTransaction(ApplicationData application, PrekinderActor actor) {
        PaymentData payment = latestPayment(application.applicationId());
        if (!application.paymentRequired() || "PAID".equals(application.paymentStatus())) {
            return wrap(response(application.applicationId(), application, payment));
        }
        validateApplication(application);
        if (payment != null && "PAYMENT_PENDING".equals(payment.status())
            && payment.chargeId() != null && !blank(payment.checkoutUrl())) {
            try { payment = reconcile(application, payment); } catch (PaymentIntegrationException ignored) { }
            return wrap(response(application.applicationId(), reloadPaymentState(application), payment));
        }

        UUID paymentId = payment == null ? UUID.randomUUID() : payment.paymentId();
        String idempotencyKey = referencePrefix() + "-" + application.applicationId();
        Instant expiresAt = LocalDate.now(providerZone).plusDays(paymentProperties.dueDays()).atStartOfDay(providerZone).toInstant();
        upsertPendingPayment(paymentId, application.applicationId(), actor.id(), idempotencyKey, expiresAt);
        jdbc.update("UPDATE applications SET payment_status = 'PAYMENT_PENDING', updated_at = now() WHERE application_id = :id",
            Map.of("id", application.applicationId()));
        audit(paymentId, "checkout.requested", Map.of("applicationId", application.applicationId(), "reference", idempotencyKey));

        AdmissionRequest admissionRequest = admissionRequest(application);
        AdmissionResponse admissionResponse = admissionGateway.synchronizeAdmission(admissionRequest);
        StudentResponse studentResponse = validateAdmission(admissionResponse, admissionRequest);
        persistSync(application.applicationId(), admissionResponse, studentResponse);

        ChargeRequest chargeRequest = chargeRequest(application, idempotencyKey);
        ChargeResponse chargeResponse = admissionGateway.createCharge(chargeRequest);
        validateCharge(chargeResponse, admissionResponse, studentResponse, chargeRequest);
        jdbc.update("""
            UPDATE prekinder_payments
               SET institutional_charge_id = :chargeId, provider_invoice_id = :invoiceId,
                   checkout_url = :checkoutUrl, external_status = :externalStatus, updated_at = now()
             WHERE payment_id = :paymentId
            """, new MapSqlParameterSource().addValue("paymentId", paymentId)
            .addValue("chargeId", chargeResponse.chargeId()).addValue("invoiceId", chargeResponse.tokuInvoiceId())
            .addValue("checkoutUrl", chargeResponse.paymentLink()).addValue("externalStatus", upper(chargeResponse.paymentStatus())));
        audit(paymentId, "charge.created", Map.of("chargeId", chargeResponse.chargeId(), "amount", chargeResponse.amount(),
            "currency", chargeResponse.currency()));
        PaymentData created = latestPayment(application.applicationId());
        if ("PAGADO".equalsIgnoreCase(chargeResponse.paymentStatus())) created = reconcile(application, created);
        return wrap(response(application.applicationId(), reloadPaymentState(application), created));
    }

    private ApplicationData loadOwned(UUID applicationId, PrekinderActor actor, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE OF a" : "";
        List<ApplicationData> rows = jdbc.query("""
            SELECT a.application_id, a.payment_required, a.payment_status, a.paid_at,
                   ap.applicant_id, ap.identity_ciphertext, ap.identity_iv, ap.identity_wrapped_dek,
                   ap.identity_wrapped_dek_iv, ap.identity_key_version,
                   fv.ciphertext AS form_ciphertext, fv.iv AS form_iv, fv.wrapped_dek AS form_wrapped_dek,
                   fv.wrapped_dek_iv AS form_wrapped_dek_iv, fv.key_version AS form_key_version
              FROM applications a
              JOIN applicants ap ON ap.applicant_id = a.applicant_id
              JOIN families f ON f.family_id = ap.family_id
              JOIN encrypted_field_values fv ON fv.aggregate_type = 'APPLICATION'
                   AND fv.aggregate_id = a.application_id AND fv.field_code = 'APPLICATION_FORM'
             WHERE a.application_id = :applicationId AND f.external_reference = :actorReference
            """ + lock, Map.of("applicationId", applicationId, "actorReference", actor.id().toString()), (rs, row) -> {
                UUID applicantId = rs.getObject("applicant_id", UUID.class);
                ApplicantIdentity identity = decrypt(new EncryptedPayload(rs.getString("identity_ciphertext"), rs.getString("identity_iv"),
                    rs.getString("identity_wrapped_dek"), rs.getString("identity_wrapped_dek_iv"), rs.getString("identity_key_version")),
                    "prekinder|applicants|" + applicantId + "|application:" + applicationId + "|identity", ApplicantIdentity.class);
                ApplicationDetails details = decrypt(new EncryptedPayload(rs.getString("form_ciphertext"), rs.getString("form_iv"),
                    rs.getString("form_wrapped_dek"), rs.getString("form_wrapped_dek_iv"), rs.getString("form_key_version")),
                    "prekinder|application-form|application:" + applicationId + "|field:APPLICATION_FORM", ApplicationDetails.class);
                return new ApplicationData(applicationId, rs.getBoolean("payment_required"), rs.getString("payment_status"),
                    instant(rs.getTimestamp("paid_at")), identity, details);
            });
        if (rows.isEmpty()) throw PaymentIntegrationException.invalidData("Postulación Prekínder no encontrada");
        return rows.get(0);
    }

    private ApplicationData reloadPaymentState(ApplicationData application) {
        return jdbc.queryForObject("SELECT payment_required, payment_status, paid_at FROM applications WHERE application_id = :id",
            Map.of("id", application.applicationId()), (rs, row) -> new ApplicationData(application.applicationId(),
                rs.getBoolean("payment_required"), rs.getString("payment_status"), instant(rs.getTimestamp("paid_at")),
                application.identity(), application.details()));
    }

    private PaymentData latestPayment(UUID applicationId) {
        List<PaymentData> rows = jdbc.query("""
            SELECT payment_id, status, institutional_charge_id, checkout_url, amount, currency,
                   expires_at, paid_at, provider_invoice_id, external_status, last_status_checked_at
              FROM prekinder_payments WHERE application_id = :applicationId
             ORDER BY created_at DESC LIMIT 1
            """, Map.of("applicationId", applicationId), (rs, row) -> new PaymentData(rs.getObject("payment_id", UUID.class),
                rs.getString("status"), (Long) rs.getObject("institutional_charge_id"), rs.getString("checkout_url"),
                rs.getBigDecimal("amount"), rs.getString("currency"), instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("paid_at")), rs.getString("provider_invoice_id"), rs.getString("external_status"),
                instant(rs.getTimestamp("last_status_checked_at"))));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void upsertPendingPayment(UUID paymentId, UUID applicationId, UUID actorId, String key, Instant expiresAt) {
        jdbc.update("""
            INSERT INTO prekinder_payments(payment_id, application_id, guardian_actor_id, provider, idempotency_key,
                amount, currency, status, external_status, expires_at)
            VALUES (:paymentId, :applicationId, :actorId, :provider, :key, :amount, :currency,
                'PAYMENT_PENDING', 'CREATING', :expiresAt)
            ON CONFLICT (idempotency_key) DO UPDATE SET amount = EXCLUDED.amount, currency = EXCLUDED.currency,
                status = 'PAYMENT_PENDING', external_status = 'CREATING', checkout_url = NULL,
                institutional_charge_id = NULL, provider_invoice_id = NULL, provider_transaction_id = NULL,
                paid_at = NULL, paid_amount = NULL, voucher = NULL, payment_method = NULL,
                expires_at = EXCLUDED.expires_at, updated_at = now()
            """, new MapSqlParameterSource().addValue("paymentId", paymentId).addValue("applicationId", applicationId)
            .addValue("actorId", actorId).addValue("provider", PROVIDER).addValue("key", key)
            .addValue("amount", paymentProperties.applicationFee()).addValue("currency", upper(paymentProperties.currency()))
            .addValue("expiresAt", Timestamp.from(expiresAt)));
    }

    private AdmissionRequest admissionRequest(ApplicationData application) {
        RutParts guardianRut = rut(application.details().guardian().rut(), "apoderado");
        RutParts studentRut = rut(application.identity().rut(), "alumno");
        return new AdmissionRequest(guardianRut.body(), guardianRut.verifier(), normalizeName(application.details().guardian().fullName()),
            normalizeEmail(application.details().guardian().email()), guardianAddress(application.details()), null,
            blank(admissionProperties.defaultCity()) ? "Santiago" : admissionProperties.defaultCity(), null,
            List.of(new StudentRequest(studentRut.body(), studentRut.verifier(), studentName(application.identity()), "PRE_KINDER")));
    }

    private ChargeRequest chargeRequest(ApplicationData application, String key) {
        RutParts guardianRut = rut(application.details().guardian().rut(), "apoderado");
        RutParts studentRut = rut(application.identity().rut(), "alumno");
        return new ChargeRequest(guardianRut.body(), guardianRut.verifier(), normalizeName(application.details().guardian().fullName()),
            normalizeEmail(application.details().guardian().email()), studentRut.body(), studentRut.verifier(),
            studentName(application.identity()), "PRE_KINDER", paymentProperties.applicationFee(), upper(paymentProperties.currency()),
            LocalDate.now(providerZone).plusDays(paymentProperties.dueDays()).toString(), paymentProperties.paymentGlosa().trim(), key);
    }

    private StudentResponse validateAdmission(AdmissionResponse response, AdmissionRequest request) {
        if (response == null || !Boolean.TRUE.equals(response.ok()) || !safe(response.errores()).isEmpty()
            || response.alumnos() == null || response.alumnos().size() != 1 || response.alumnos().get(0) == null) {
            throw PaymentIntegrationException.schoolValidation("El colegio no pudo sincronizar la postulación Prekínder");
        }
        StudentResponse student = response.alumnos().get(0);
        StudentRequest expectedStudent = request.alumnos().get(0);
        if (!sameRut(response.guardianRut(), request.value(), request.valueValidator())
            || !sameRut(student.rut(), expectedStudent.value(), expectedStudent.valueValidator())) {
            throw PaymentIntegrationException.schoolValidation("La identidad devuelta por el colegio no corresponde a la postulación");
        }
        if (!GUARDIAN_STATES.contains(lower(response.guardianState())) || response.businessPartnerId() == null
            || !STUDENT_STATES.contains(lower(student.estado())) || student.userId() == null
            || !CUSTOMER_STATES.contains(lower(response.tokuCustomerState())) || blank(response.tokuCustomerId())
            || !STUDENT_STATES.contains(lower(student.tokuSubscriptionState())) || blank(student.tokuSubscriptionId())) {
            throw PaymentIntegrationException.schoolValidation("El colegio no confirmó correctamente al apoderado y postulante");
        }
        return student;
    }

    private void validateCharge(ChargeResponse response, AdmissionResponse admission, StudentResponse student, ChargeRequest request) {
        if (response == null || !Boolean.TRUE.equals(response.ok()) || response.chargeId() == null
            || !("OK".equalsIgnoreCase(response.estado()) || "YA_EXISTIA".equalsIgnoreCase(response.estado()))) {
            throw PaymentIntegrationException.schoolValidation("El colegio no pudo crear el cobro de Prekínder");
        }
        if (response.amount() == null || response.amount().compareTo(request.amount()) != 0
            || blank(response.currency()) || !response.currency().equalsIgnoreCase(request.currency())
            || !admission.businessPartnerId().equals(response.businessPartnerId())
            || !student.userId().equals(response.studentUserId())
            || (!blank(response.externalReference()) && !request.externalReference().equals(response.externalReference()))) {
            throw PaymentIntegrationException.schoolValidation("El cobro creado no coincide con la postulación Prekínder");
        }
        if (!"PAGADO".equalsIgnoreCase(response.paymentStatus())) validatePaymentLink(response.paymentLink());
    }

    private PaymentData reconcile(ApplicationData application, PaymentData payment) {
        ChargeStatusResponse response = admissionGateway.chargeStatus(payment.chargeId());
        Instant checkedAt = Instant.now();
        if (response == null || Boolean.FALSE.equals(response.encontrado())) {
            jdbc.update("UPDATE prekinder_payments SET last_status_checked_at = now(), updated_at = now() WHERE payment_id = :id",
                Map.of("id", payment.paymentId()));
            throw PaymentIntegrationException.unavailable("El colegio no encontró el cobro de Prekínder");
        }
        boolean paid = Boolean.TRUE.equals(response.pagado()) || "PAGADO".equalsIgnoreCase(response.estado());
        if (paid) {
            if (blank(response.currency()) || !payment.currency().equalsIgnoreCase(response.currency())
                || response.paidAmount() == null || response.paidAmount().compareTo(payment.amount()) < 0) {
                throw PaymentIntegrationException.schoolValidation("El monto pagado no coincide con el cobro de Prekínder");
            }
            Instant paidAt = parseSchoolDate(response.paidAt()).atZone(providerZone).toInstant();
            jdbc.update("""
                UPDATE prekinder_payments SET status = 'PAID', paid_at = :paidAt, paid_amount = :paidAmount,
                    provider_transaction_id = :transactionId, voucher = :voucher, payment_method = :method,
                    external_status = :externalStatus, checkout_url = COALESCE(:checkoutUrl, checkout_url),
                    last_status_checked_at = :checkedAt, updated_at = now() WHERE payment_id = :id
                """, new MapSqlParameterSource().addValue("id", payment.paymentId()).addValue("paidAt", Timestamp.from(paidAt))
                .addValue("paidAmount", response.paidAmount()).addValue("transactionId", response.transactionId())
                .addValue("voucher", response.voucher()).addValue("method", response.paymentMethod())
                .addValue("externalStatus", upper(response.estado())).addValue("checkoutUrl", response.paymentLink())
                .addValue("checkedAt", Timestamp.from(checkedAt)));
            jdbc.update("UPDATE applications SET payment_status = 'PAID', paid_at = :paidAt, updated_at = now() WHERE application_id = :id",
                new MapSqlParameterSource().addValue("id", application.applicationId()).addValue("paidAt", Timestamp.from(paidAt)));
        } else {
            jdbc.update("""
                UPDATE prekinder_payments SET external_status = :externalStatus,
                    checkout_url = COALESCE(:checkoutUrl, checkout_url), last_status_checked_at = :checkedAt,
                    updated_at = now() WHERE payment_id = :id
                """, new MapSqlParameterSource().addValue("id", payment.paymentId())
                .addValue("externalStatus", upper(response.estado())).addValue("checkoutUrl", response.paymentLink())
                .addValue("checkedAt", Timestamp.from(checkedAt)));
        }
        audit(payment.paymentId(), "status.checked", Map.of("paid", paid, "status", upper(response.estado())));
        return latestPayment(application.applicationId());
    }

    private void persistSync(UUID applicationId, AdmissionResponse response, StudentResponse student) {
        jdbc.update("""
            INSERT INTO prekinder_application_school_syncs(sync_id, application_id, business_partner_id,
                business_partner_location_id, student_user_id, toku_customer_id, toku_subscription_id,
                sync_status, guardian_state, customer_state, student_state, subscription_state,
                last_attempt_at, last_success_at)
            VALUES (:id, :applicationId, :partnerId, :locationId, :studentId, :customerId, :subscriptionId,
                'SYNCED', :guardianState, :customerState, :studentState, :subscriptionState, now(), now())
            ON CONFLICT (application_id) DO UPDATE SET business_partner_id = EXCLUDED.business_partner_id,
                business_partner_location_id = EXCLUDED.business_partner_location_id,
                student_user_id = EXCLUDED.student_user_id, toku_customer_id = EXCLUDED.toku_customer_id,
                toku_subscription_id = EXCLUDED.toku_subscription_id, sync_status = 'SYNCED',
                guardian_state = EXCLUDED.guardian_state, customer_state = EXCLUDED.customer_state,
                student_state = EXCLUDED.student_state, subscription_state = EXCLUDED.subscription_state,
                last_attempt_at = now(), last_success_at = now(), updated_at = now()
            """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("applicationId", applicationId)
            .addValue("partnerId", response.businessPartnerId()).addValue("locationId", response.businessPartnerLocationId())
            .addValue("studentId", student.userId()).addValue("customerId", response.tokuCustomerId())
            .addValue("subscriptionId", student.tokuSubscriptionId()).addValue("guardianState", response.guardianState())
            .addValue("customerState", response.tokuCustomerState()).addValue("studentState", student.estado())
            .addValue("subscriptionState", student.tokuSubscriptionState()));
    }

    private void markFailed(UUID applicationId, UUID actorId) {
        jdbc.update("""
            UPDATE prekinder_payments SET status = 'FAILED', external_status = 'FAILED', updated_at = now()
             WHERE application_id = :applicationId AND guardian_actor_id = :actorId
               AND status <> 'PAID' AND institutional_charge_id IS NULL
            """, Map.of("applicationId", applicationId, "actorId", actorId));
        jdbc.update("""
            UPDATE applications SET payment_status = 'FAILED', updated_at = now()
             WHERE application_id = :id AND payment_status <> 'PAID'
               AND NOT EXISTS (SELECT 1 FROM prekinder_payments p
                                WHERE p.application_id = :id AND p.institutional_charge_id IS NOT NULL)
            """,
            Map.of("id", applicationId));
    }

    private Map<String, Object> response(UUID applicationId, ApplicationData application, PaymentData payment) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", applicationId);
        data.put("source", "PREKINDER");
        data.put("paymentRequired", application.paymentRequired());
        data.put("paymentStatus", application.paymentStatus());
        data.put("paidAt", application.paidAt());
        data.put("canFillComplementaryForm", !application.paymentRequired() || "PAID".equals(application.paymentStatus()));
        if (payment != null) {
            data.put("paymentId", payment.paymentId());
            data.put("checkoutUrl", payment.checkoutUrl());
            data.put("amount", payment.amount());
            data.put("currency", payment.currency());
            data.put("expiresAt", payment.expiresAt());
            data.put("providerInvoiceId", payment.invoiceId());
            data.put("providerStatus", payment.externalStatus());
            data.put("lastStatusCheckedAt", payment.lastCheckedAt());
        } else {
            data.put("amount", paymentProperties.applicationFee());
            data.put("currency", upper(paymentProperties.currency()));
        }
        return data;
    }

    private void validateApplication(ApplicationData application) {
        if (application.details().guardian() == null || blank(application.details().guardian().fullName())) {
            throw PaymentIntegrationException.invalidData("La postulación Prekínder no tiene un apoderado válido");
        }
        if (blank(application.details().guardian().email()) || !application.details().guardian().email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw PaymentIntegrationException.invalidData("El apoderado no tiene un correo válido para MTN Pay");
        }
        if (blank(guardianAddress(application.details()))) {
            throw PaymentIntegrationException.invalidData("El apoderado no tiene una dirección disponible para MTN Pay");
        }
        rut(application.details().guardian().rut(), "apoderado");
        rut(application.identity().rut(), "alumno");
    }

    private String guardianAddress(ApplicationDetails details) {
        String relation = lower(details.guardian().relationship());
        if (relation.contains("madre") || relation.equals("mother")) return details.mother() == null ? null : details.mother().address();
        if (relation.contains("padre") || relation.equals("father")) return details.father() == null ? null : details.father().address();
        String mother = details.mother() == null ? null : details.mother().address();
        return blank(mother) ? (details.father() == null ? null : details.father().address()) : mother;
    }

    private void audit(UUID paymentId, String type, Map<String, ?> payload) {
        try {
            jdbc.update("""
                INSERT INTO prekinder_payment_events(event_id, payment_id, event_type, payload)
                VALUES (:id, :paymentId, :type, CAST(:payload AS jsonb))
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("paymentId", paymentId)
                .addValue("type", type).addValue("payload", mapper.writeValueAsString(payload)));
        } catch (Exception exception) {
            throw new IllegalStateException("No fue posible auditar el pago de Prekínder", exception);
        }
    }

    private <T> T decrypt(EncryptedPayload payload, String aad, Class<T> type) {
        try { return mapper.readValue(encryption.decrypt(payload, aad), type); }
        catch (Exception exception) { throw PaymentIntegrationException.invalidData("Los datos cifrados de la postulación no son válidos"); }
    }

    private String referencePrefix() {
        return blank(paymentProperties.referencePrefix()) ? "ADMITIA-PK" : paymentProperties.referencePrefix().trim();
    }

    private static RutParts rut(String value, String label) {
        String normalized = value == null ? "" : value.replaceAll("[^0-9Kk]", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9]{7,8}[0-9K]")) throw PaymentIntegrationException.invalidData("El RUT de " + label + " es inválido");
        String body = normalized.substring(0, normalized.length() - 1);
        String verifier = normalized.substring(normalized.length() - 1);
        int sum = 0;
        int multiplier = 2;
        for (int index = body.length() - 1; index >= 0; index--) {
            sum += Character.digit(body.charAt(index), 10) * multiplier;
            multiplier = multiplier == 7 ? 2 : multiplier + 1;
        }
        int result = 11 - (sum % 11);
        String expected = result == 11 ? "0" : result == 10 ? "K" : String.valueOf(result);
        if (!expected.equals(verifier)) throw PaymentIntegrationException.invalidData("El dígito verificador del RUT de " + label + " es inválido");
        return new RutParts(body, verifier);
    }

    private static boolean sameRut(String formatted, String body, String verifier) {
        String actual = formatted == null ? "" : formatted.replaceAll("[^0-9Kk]", "").toUpperCase(Locale.ROOT);
        return actual.equals(body + verifier);
    }

    private static void validatePaymentLink(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || blank(uri.getHost())) {
                throw PaymentIntegrationException.missingLink();
            }
        } catch (IllegalArgumentException exception) { throw PaymentIntegrationException.missingLink(); }
    }

    private static LocalDateTime parseSchoolDate(String value) {
        if (blank(value)) return LocalDateTime.now();
        try { return LocalDateTime.parse(value.trim(), SCHOOL_DATE_TIME); }
        catch (DateTimeParseException exception) { return LocalDateTime.now(); }
    }

    private static String studentName(ApplicantIdentity identity) {
        return normalizeName((identity.firstName() + " " + identity.paternalLastName() + " " + nullToEmpty(identity.maternalLastName())).trim());
    }
    private static String normalizeName(String value) { return nullToEmpty(value).trim().replaceAll("\\s+", " "); }
    private static String normalizeEmail(String value) { return blank(value) ? null : value.trim().toLowerCase(Locale.ROOT); }
    private static String upper(String value) { return blank(value) ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String lower(String value) { return blank(value) ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Map<String, Object> wrap(Map<String, Object> data) { return Map.of("success", true, "data", data); }

    private record ApplicationData(UUID applicationId, boolean paymentRequired, String paymentStatus, Instant paidAt,
                                   ApplicantIdentity identity, ApplicationDetails details) { }
    private record PaymentData(UUID paymentId, String status, Long chargeId, String checkoutUrl, BigDecimal amount,
                               String currency, Instant expiresAt, Instant paidAt, String invoiceId,
                               String externalStatus, Instant lastCheckedAt) { }
    private record RutParts(String body, String verifier) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ApplicantIdentity(String rut, String firstName, String paternalLastName, String maternalLastName) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ApplicationDetails(Address address, Adult father, Adult mother, Guardian guardian) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Address(String street, String number, String apartment, String country, String region, String commune) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Adult(String fullName, String rut, String email, String phone, String address) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Guardian(String fullName, String rut, String email, String phone, String relationship) { }
}
