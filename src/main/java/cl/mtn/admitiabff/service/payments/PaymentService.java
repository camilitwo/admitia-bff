package cl.mtn.admitiabff.service.payments;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.PaymentStatus;
import cl.mtn.admitiabff.domain.payment.ApplicationSchoolSyncEntity;
import cl.mtn.admitiabff.domain.payment.PaymentEntity;
import cl.mtn.admitiabff.domain.payment.PaymentEventEntity;
import cl.mtn.admitiabff.domain.person.GuardianEntity;
import cl.mtn.admitiabff.domain.person.ParentEntity;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.ApplicationSchoolSyncRepository;
import cl.mtn.admitiabff.repository.PaymentEventRepository;
import cl.mtn.admitiabff.repository.PaymentRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.AdmissionRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.AdmissionResponse;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeResponse;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeStatusResponse;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.StudentRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.StudentResponse;
import cl.mtn.admitiabff.util.JsonSupport;
import java.math.BigDecimal;
import java.net.URI;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String PROVIDER = "MTN_ADMISSION_API";
    private static final Set<String> SUCCESSFUL_GUARDIAN_STATES = Set.of("creado", "ya_existia");
    private static final Set<String> SUCCESSFUL_CUSTOMER_STATES = Set.of("creado", "ya_existia", "ya_existia_toku");
    private static final Set<String> SUCCESSFUL_STUDENT_STATES = Set.of("creado", "ya_existia");
    private static final DateTimeFormatter SCHOOL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final ApplicationSchoolSyncRepository schoolSyncRepository;
    private final MtnAdmissionGateway admissionClient;
    private final MtnAdmissionProperties properties;
    private final JsonSupport jsonSupport;
    private final ZoneId providerZone;

    public PaymentService(ApplicationRepository applicationRepository,
                          UserRepository userRepository,
                          PaymentRepository paymentRepository,
                          PaymentEventRepository paymentEventRepository,
                          ApplicationSchoolSyncRepository schoolSyncRepository,
                          MtnAdmissionGateway admissionClient,
                          MtnAdmissionProperties properties,
                          JsonSupport jsonSupport) {
        this.applicationRepository = applicationRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.schoolSyncRepository = schoolSyncRepository;
        this.admissionClient = admissionClient;
        this.properties = properties;
        this.jsonSupport = jsonSupport;
        this.providerZone = ZoneId.of(blank(properties.providerZone()) ? "America/Santiago" : properties.providerZone());
    }

    @Transactional(noRollbackFor = PaymentIntegrationException.class)
    public Map<String, Object> checkout(Long applicationId, Long userId) {
        properties.validateForUse();
        ApplicationEntity application = loadOwnedApplicationForUpdate(applicationId, userId);
        PaymentEntity latest = paymentRepository.findFirstByApplicationIdOrderByCreatedAtDesc(applicationId).orElse(null);
        if (!application.isPaymentRequired() || application.getPaymentStatus() == PaymentStatus.PAID) {
            return wrap(statusResponse(application, latest));
        }

        validateApplicationData(application);
        PaymentEntity payment = latest != null
            ? latest
            : paymentRepository.findByIdempotencyKey(idempotencyKey(applicationId)).orElse(null);
        if (payment != null && payment.getStatus() == PaymentStatus.PAYMENT_PENDING
            && payment.getInstitutionalChargeId() != null && !blank(payment.getCheckoutUrl())) {
            try {
                reconcile(payment);
            } catch (PaymentIntegrationException ex) {
                audit(payment, "status.check_failed", Map.of("code", ex.code()));
            }
            return wrap(statusResponse(application, payment));
        }

        if (payment == null) payment = newPayment(application, userId);
        resetForAttempt(payment);
        application.setPaymentStatus(PaymentStatus.PAYMENT_PENDING);
        applicationRepository.save(application);
        paymentRepository.save(payment);
        audit(payment, "checkout.requested", Map.of("applicationId", applicationId, "reference", payment.getIdempotencyKey()));

        try {
            ApplicationSchoolSyncEntity schoolSync = synchronizeApplication(application, payment);
            ChargeRequest chargePayload = chargeRequest(application, payment);
            log.info("[mtn-payment] operation=charge.create applicationId={} paymentId={} reference={} amount={} currency={} dueDate={} course={}",
                application.getId(), payment.getId(), chargePayload.externalReference(), chargePayload.amount(),
                chargePayload.currency(), chargePayload.dueDate(), nullToEmpty(chargePayload.studentCourse()));
            ChargeResponse charge = admissionClient.createCharge(chargePayload);
            log.info("[mtn-payment] operation=charge.create applicationId={} paymentId={} apiState={} paymentState={} chargeId={} invoicePresent={} paymentLinkPresent={} warningCount={} errorCount={}",
                application.getId(), payment.getId(), nullToEmpty(charge.estado()), nullToEmpty(charge.paymentStatus()),
                charge.chargeId(), !blank(charge.tokuInvoiceId()), !blank(charge.paymentLink()),
                safeList(charge.advertencias()).size(), safeList(charge.errores()).size());
            validateCharge(charge, payment, schoolSync);
            payment.setInstitutionalChargeId(charge.chargeId());
            payment.setProviderInvoiceId(charge.tokuInvoiceId());
            payment.setCheckoutUrl(charge.paymentLink());
            payment.setExternalStatus(upper(charge.paymentStatus()));
            paymentRepository.save(payment);
            audit(payment, "charge.created", sanitizedCharge(charge));
            if ("PAGADO".equalsIgnoreCase(charge.paymentStatus())) reconcile(payment);
            return wrap(statusResponse(application, payment));
        } catch (PaymentIntegrationException ex) {
            boolean chargeAlreadyCreated = payment.getInstitutionalChargeId() != null;
            payment.setStatus(chargeAlreadyCreated ? PaymentStatus.PAYMENT_PENDING : PaymentStatus.FAILED);
            application.setPaymentStatus(chargeAlreadyCreated ? PaymentStatus.PAYMENT_PENDING : PaymentStatus.FAILED);
            paymentRepository.save(payment);
            applicationRepository.save(application);
            audit(payment, chargeAlreadyCreated ? "reconciliation.deferred" : "checkout.failed", Map.of("code", ex.code()));
            throw ex;
        }
    }

    @Transactional(noRollbackFor = PaymentIntegrationException.class)
    public Map<String, Object> status(Long applicationId, Long userId) {
        ApplicationEntity application = loadOwnedApplication(applicationId, userId);
        PaymentEntity payment = paymentRepository.findFirstByApplicationIdOrderByCreatedAtDesc(applicationId).orElse(null);
        if (payment != null && payment.getStatus() == PaymentStatus.PAYMENT_PENDING && payment.getInstitutionalChargeId() != null) {
            reconcile(payment);
        }
        return wrap(statusResponse(application, payment));
    }

    @Transactional
    public void reconcilePendingForUserBestEffort(Long userId) {
        if (!properties.enabled()) return;
        List<PaymentEntity> pending = paymentRepository
            .findByGuardianUserIdAndStatusAndInstitutionalChargeIdIsNotNull(userId, PaymentStatus.PAYMENT_PENDING);
        for (PaymentEntity payment : pending) {
            try {
                reconcile(payment);
            } catch (RuntimeException ex) {
                log.warn("[payments] No se pudo conciliar paymentId={} applicationId={}: {}",
                    payment.getId(), payment.getApplication().getId(), ex.getMessage());
                audit(payment, "status.check_failed", Map.of("type", ex.getClass().getSimpleName()));
            }
        }
    }

    private ApplicationSchoolSyncEntity synchronizeApplication(ApplicationEntity application, PaymentEntity payment) {
        ApplicationSchoolSyncEntity sync = schoolSyncRepository.findByApplicationId(application.getId()).orElseGet(() -> {
            ApplicationSchoolSyncEntity created = new ApplicationSchoolSyncEntity();
            created.setApplication(application);
            return created;
        });
        sync.setSyncStatus("SYNCING");
        sync.setLastAttemptAt(LocalDateTime.now(providerZone));
        schoolSyncRepository.save(sync);
        try {
            AdmissionRequest request = admissionRequest(application);
            log.info("[mtn-payment] operation=admission.sync applicationId={} paymentId={} studentCount={} course={} emailPresent={} addressPresent={}",
                application.getId(), payment.getId(), request.alumnos().size(), nullToEmpty(request.alumnos().get(0).codCurso()),
                !blank(request.email()), !blank(request.address1()));
            AdmissionResponse response = admissionClient.synchronizeAdmission(request);
            persistAdmissionResponse(sync, response);
            StudentResponse studentResult = firstStudent(response);
            log.info("[mtn-payment] operation=admission.sync applicationId={} paymentId={} apiState={} guardianState={} customerState={} studentState={} subscriptionState={} businessPartnerId={} studentUserId={} warningCount={} errorCount={}",
                application.getId(), payment.getId(), nullToEmpty(response.estado()), nullToEmpty(response.guardianState()),
                nullToEmpty(response.tokuCustomerState()), nullToEmpty(studentResult.estado()),
                nullToEmpty(studentResult.tokuSubscriptionState()), response.businessPartnerId(), studentResult.userId(),
                safeList(response.advertencias()).size(), safeList(response.errores()).size());
            validateAdmission(response, request);
            sync.setSyncStatus("SYNCED");
            sync.setLastSuccessAt(LocalDateTime.now(providerZone));
            schoolSyncRepository.save(sync);
            audit(payment, "admission.synchronized", sanitizedAdmission(response));
            return sync;
        } catch (PaymentIntegrationException ex) {
            log.warn("[mtn-payment] operation=admission.sync applicationId={} paymentId={} completed=false code={}",
                application.getId(), payment.getId(), ex.code());
            sync.setSyncStatus("FAILED");
            sync.setErrors(jsonSupport.write(List.of(ex.code())));
            schoolSyncRepository.save(sync);
            throw ex;
        }
    }

    private AdmissionRequest admissionRequest(ApplicationEntity application) {
        GuardianEntity guardian = application.getGuardian();
        StudentEntity student = application.getStudent();
        ChileanRut.Parts guardianRut = ChileanRut.parse(guardian.getRut(), "apoderado");
        ChileanRut.Parts studentRut = ChileanRut.parse(student.getRut(), "alumno");
        return new AdmissionRequest(
            guardianRut.body(), guardianRut.verifier(), normalizedName(guardian.getFullName()), guardianEmail(application),
            guardianAddress(application), null,
            blank(properties.defaultCity()) ? "Santiago" : properties.defaultCity(), null,
            List.of(new StudentRequest(studentRut.body(), studentRut.verifier(), studentName(student), courseCode(student.getGradeApplied())))
        );
    }

    private ChargeRequest chargeRequest(ApplicationEntity application, PaymentEntity payment) {
        GuardianEntity guardian = application.getGuardian();
        StudentEntity student = application.getStudent();
        ChileanRut.Parts guardianRut = ChileanRut.parse(guardian.getRut(), "apoderado");
        ChileanRut.Parts studentRut = ChileanRut.parse(student.getRut(), "alumno");
        return new ChargeRequest(
            guardianRut.body(), guardianRut.verifier(), normalizedName(guardian.getFullName()), guardianEmail(application),
            studentRut.body(), studentRut.verifier(), studentName(student), courseCode(student.getGradeApplied()),
            payment.getAmount(), payment.getCurrency(), LocalDate.now(providerZone).plusDays(properties.dueDays()).toString(),
            properties.paymentGlosa().trim(), payment.getIdempotencyKey()
        );
    }

    private void validateAdmission(AdmissionResponse response, AdmissionRequest request) {
        if (response == null || !Boolean.TRUE.equals(response.ok()) || !safeList(response.errores()).isEmpty()) {
            throw PaymentIntegrationException.schoolValidation("El colegio no pudo sincronizar al apoderado y alumno");
        }
        StudentResponse student = firstStudent(response);
        if (response.alumnos().size() != 1 || request.alumnos().size() != 1) {
            throw PaymentIntegrationException.schoolValidation("El colegio devolvió un número inesperado de alumnos");
        }
        if (!sameRut(response.guardianRut(), request.value(), request.valueValidator())) {
            throw PaymentIntegrationException.schoolValidation("El apoderado devuelto por el colegio no corresponde a la postulación");
        }
        StudentRequest requestedStudent = request.alumnos().get(0);
        if (!sameRut(student.rut(), requestedStudent.value(), requestedStudent.valueValidator())) {
            throw PaymentIntegrationException.schoolValidation("El alumno devuelto por el colegio no corresponde a la postulación");
        }
        if (!SUCCESSFUL_GUARDIAN_STATES.contains(lower(response.guardianState())) || response.businessPartnerId() == null) {
            throw PaymentIntegrationException.schoolValidation("El colegio rechazó la sincronización del apoderado");
        }
        if (!SUCCESSFUL_STUDENT_STATES.contains(lower(student.estado()))) {
            throw PaymentIntegrationException.schoolValidation("El colegio rechazó la sincronización del alumno");
        }
        if (student.userId() == null) {
            throw PaymentIntegrationException.schoolValidation("El colegio no informó el identificador institucional del alumno");
        }
        if (!SUCCESSFUL_CUSTOMER_STATES.contains(lower(response.tokuCustomerState())) || blank(response.tokuCustomerId())) {
            throw PaymentIntegrationException.schoolValidation("El cliente MTN Pay del apoderado no quedó confirmado");
        }
        if (!SUCCESSFUL_STUDENT_STATES.contains(lower(student.tokuSubscriptionState())) || blank(student.tokuSubscriptionId())) {
            throw PaymentIntegrationException.schoolValidation("La suscripción MTN Pay del alumno no quedó confirmada");
        }
    }

    private void validateCharge(ChargeResponse response, PaymentEntity payment, ApplicationSchoolSyncEntity schoolSync) {
        if (response == null || !Boolean.TRUE.equals(response.ok())
            || !("OK".equalsIgnoreCase(response.estado()) || "YA_EXISTIA".equalsIgnoreCase(response.estado()))
            || response.chargeId() == null) {
            throw PaymentIntegrationException.schoolValidation("El colegio no pudo crear el cobro de la postulación");
        }
        if (response.amount() == null || response.amount().compareTo(payment.getAmount()) != 0) {
            throw PaymentIntegrationException.schoolValidation("El monto creado por el colegio no coincide con el configurado");
        }
        if (blank(response.currency()) || !payment.getCurrency().equalsIgnoreCase(response.currency())) {
            throw PaymentIntegrationException.schoolValidation("La moneda creada por el colegio no coincide con la configurada");
        }
        if (response.businessPartnerId() == null || !response.businessPartnerId().equals(schoolSync.getBusinessPartnerId())) {
            throw PaymentIntegrationException.schoolValidation("El cobro no corresponde al apoderado sincronizado");
        }
        if (response.studentUserId() == null || !response.studentUserId().equals(schoolSync.getStudentUserId())) {
            throw PaymentIntegrationException.schoolValidation("El cobro no corresponde al alumno sincronizado");
        }
        if (!blank(response.externalReference()) && !payment.getIdempotencyKey().equals(response.externalReference())) {
            throw PaymentIntegrationException.schoolValidation("La referencia del cobro no corresponde a la postulación");
        }
        if (!"PAGADO".equalsIgnoreCase(response.paymentStatus())) validatePaymentLink(response.paymentLink());
    }

    private static boolean sameRut(String formattedRut, String body, String verifier) {
        String actual = nullToEmpty(formattedRut).replaceAll("[^0-9Kk]", "").toUpperCase(Locale.ROOT);
        String expected = nullToEmpty(body).replaceAll("[^0-9]", "")
            + nullToEmpty(verifier).replaceAll("[^0-9Kk]", "").toUpperCase(Locale.ROOT);
        return !actual.isBlank() && actual.equals(expected);
    }

    private void validatePaymentLink(String link) {
        if (blank(link)) throw PaymentIntegrationException.missingLink();
        try {
            URI uri = URI.create(link);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || blank(uri.getHost())) {
                throw PaymentIntegrationException.missingLink();
            }
        } catch (IllegalArgumentException ex) {
            throw PaymentIntegrationException.missingLink();
        }
    }

    private void reconcile(PaymentEntity payment) {
        log.info("[mtn-payment] operation=charge.status applicationId={} paymentId={} chargeId={} started=true",
            payment.getApplication().getId(), payment.getId(), payment.getInstitutionalChargeId());
        ChargeStatusResponse response = admissionClient.chargeStatus(payment.getInstitutionalChargeId());
        log.info("[mtn-payment] operation=charge.status applicationId={} paymentId={} chargeId={} found={} paid={} apiState={} paidAmountPresent={} paymentLinkPresent={}",
            payment.getApplication().getId(), payment.getId(), payment.getInstitutionalChargeId(),
            response != null && !Boolean.FALSE.equals(response.encontrado()), response != null && Boolean.TRUE.equals(response.pagado()),
            response == null ? "" : nullToEmpty(response.estado()), response != null && response.paidAmount() != null,
            response != null && !blank(response.paymentLink()));
        payment.setLastStatusCheckedAt(LocalDateTime.now(providerZone));
        if (response == null || Boolean.FALSE.equals(response.encontrado())) {
            paymentRepository.save(payment);
            throw PaymentIntegrationException.unavailable("El colegio no encontró el cobro registrado");
        }
        payment.setExternalStatus(upper(response.estado()));
        if (!blank(response.paymentLink())) payment.setCheckoutUrl(response.paymentLink());
        if (Boolean.TRUE.equals(response.pagado()) || "PAGADO".equalsIgnoreCase(response.estado())) {
            validatePaidAmounts(payment, response);
            markPaid(payment, response);
        } else {
            paymentRepository.save(payment);
        }
        audit(payment, "status.checked", sanitizedStatus(response));
    }

    private void validatePaidAmounts(PaymentEntity payment, ChargeStatusResponse response) {
        if (!payment.getCurrency().equalsIgnoreCase(response.currency())) {
            throw PaymentIntegrationException.schoolValidation("La moneda confirmada por el colegio no coincide con el cobro");
        }
        if (response.paidAmount() == null || response.paidAmount().compareTo(payment.getAmount()) < 0) {
            throw PaymentIntegrationException.schoolValidation("El monto confirmado por el colegio no cubre el cobro esperado");
        }
    }

    private void markPaid(PaymentEntity payment, ChargeStatusResponse response) {
        if (payment.getStatus() == PaymentStatus.PAID) return;
        LocalDateTime paidAt = parseSchoolDate(response.paidAt());
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(paidAt);
        payment.setPaidAmount(response.paidAmount());
        payment.setProviderTransactionId(response.transactionId());
        payment.setVoucher(response.voucher());
        payment.setPaymentMethod(response.paymentMethod());
        paymentRepository.save(payment);
        ApplicationEntity application = payment.getApplication();
        application.setPaymentStatus(PaymentStatus.PAID);
        application.setPaidAt(paidAt);
        applicationRepository.save(application);
    }

    private PaymentEntity newPayment(ApplicationEntity application, Long userId) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> PaymentIntegrationException.invalidData("Usuario autenticado no encontrado"));
        PaymentEntity payment = new PaymentEntity();
        payment.setApplication(application);
        payment.setGuardianUser(user);
        payment.setIdempotencyKey(idempotencyKey(application.getId()));
        return payment;
    }

    private void resetForAttempt(PaymentEntity payment) {
        payment.setProvider(PROVIDER);
        payment.setAmount(properties.applicationFee());
        payment.setCurrency(upper(properties.currency()));
        payment.setStatus(PaymentStatus.PAYMENT_PENDING);
        payment.setCheckoutUrl(null);
        payment.setInstitutionalChargeId(null);
        payment.setProviderInvoiceId(null);
        payment.setProviderTransactionId(null);
        payment.setPaidAmount(null);
        payment.setVoucher(null);
        payment.setPaymentMethod(null);
        payment.setExternalStatus("CREATING");
        payment.setPaidAt(null);
        payment.setExpiresAt(LocalDate.now(providerZone).plusDays(properties.dueDays()).atStartOfDay());
    }

    private void validateApplicationData(ApplicationEntity application) {
        if (application.getGuardian() == null) throw PaymentIntegrationException.invalidData("La postulación no tiene un apoderado asociado");
        if (application.getStudent() == null) throw PaymentIntegrationException.invalidData("La postulación no tiene un alumno asociado");
        if (blank(application.getGuardian().getFullName())) throw PaymentIntegrationException.invalidData("El apoderado no tiene nombre");
        if (blank(studentName(application.getStudent()))) throw PaymentIntegrationException.invalidData("El alumno no tiene nombre");
        String email = guardianEmail(application);
        if (blank(email) || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw PaymentIntegrationException.invalidData("El apoderado no tiene un correo válido para MTN Pay");
        }
        if (blank(guardianAddress(application))) {
            throw PaymentIntegrationException.invalidData("El apoderado no tiene una dirección disponible para MTN Pay");
        }
        ChileanRut.parse(application.getGuardian().getRut(), "apoderado");
        ChileanRut.parse(application.getStudent().getRut(), "alumno");
    }

    private ApplicationEntity loadOwnedApplication(Long applicationId, Long userId) {
        ApplicationEntity application = applicationRepository.findActiveById(applicationId)
            .orElseThrow(() -> PaymentIntegrationException.invalidData("Postulación no encontrada"));
        assertOwnership(application, userId);
        return application;
    }

    private ApplicationEntity loadOwnedApplicationForUpdate(Long applicationId, Long userId) {
        ApplicationEntity application = applicationRepository.findActiveByIdForUpdate(applicationId)
            .orElseThrow(() -> PaymentIntegrationException.invalidData("Postulación no encontrada"));
        assertOwnership(application, userId);
        return application;
    }

    private void assertOwnership(ApplicationEntity application, Long userId) {
        if (application.getApplicantUser() == null || !userId.equals(application.getApplicantUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puede operar sobre esta postulación");
        }
    }

    private void persistAdmissionResponse(ApplicationSchoolSyncEntity sync, AdmissionResponse response) {
        if (response == null) throw PaymentIntegrationException.schoolValidation("El colegio devolvió una respuesta de alta vacía");
        StudentResponse student = firstStudent(response);
        sync.setBusinessPartnerId(response.businessPartnerId());
        sync.setBusinessPartnerLocationId(response.businessPartnerLocationId());
        sync.setStudentUserId(student.userId());
        sync.setTokuCustomerId(response.tokuCustomerId());
        sync.setTokuSubscriptionId(student.tokuSubscriptionId());
        sync.setGuardianState(response.guardianState());
        sync.setCustomerState(response.tokuCustomerState());
        sync.setStudentState(student.estado());
        sync.setSubscriptionState(student.tokuSubscriptionState());
        sync.setWarnings(jsonSupport.write(safeList(response.advertencias())));
        sync.setErrors(jsonSupport.write(safeList(response.errores())));
        schoolSyncRepository.save(sync);
    }

    private StudentResponse firstStudent(AdmissionResponse response) {
        if (response.alumnos() == null || response.alumnos().isEmpty() || response.alumnos().get(0) == null) {
            throw PaymentIntegrationException.schoolValidation("El colegio no informó el resultado del alumno");
        }
        return response.alumnos().get(0);
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

    private Map<String, Object> wrap(Map<String, Object> data) { return Map.of("success", true, "data", data); }

    private void audit(PaymentEntity payment, String eventType, Object payload) {
        PaymentEventEntity event = new PaymentEventEntity();
        event.setPayment(payment);
        event.setProvider(PROVIDER);
        event.setEventType(eventType);
        event.setPayload(jsonSupport.write(payload));
        paymentEventRepository.save(event);
    }

    private Map<String, Object> sanitizedAdmission(AdmissionResponse response) {
        StudentResponse student = firstStudent(response);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("estado", response.estado());
        result.put("businessPartnerId", response.businessPartnerId());
        result.put("studentUserId", student.userId());
        result.put("guardianState", response.guardianState());
        result.put("customerState", response.tokuCustomerState());
        result.put("studentState", student.estado());
        result.put("subscriptionState", student.tokuSubscriptionState());
        result.put("warnings", safeList(response.advertencias()));
        return result;
    }

    private Map<String, Object> sanitizedCharge(ChargeResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("estado", response.estado());
        result.put("chargeId", response.chargeId());
        result.put("invoiceId", nullToEmpty(response.tokuInvoiceId()));
        result.put("amount", response.amount());
        result.put("currency", nullToEmpty(response.currency()));
        return result;
    }

    private Map<String, Object> sanitizedStatus(ChargeStatusResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chargeId", response.chargeId());
        result.put("estado", nullToEmpty(response.estado()));
        result.put("paid", Boolean.TRUE.equals(response.pagado()));
        result.put("amount", response.amount());
        result.put("paidAmount", response.paidAmount());
        result.put("currency", nullToEmpty(response.currency()));
        return result;
    }

    private String idempotencyKey(Long applicationId) {
        String prefix = blank(properties.referencePrefix()) ? "ADMITIA" : properties.referencePrefix().trim();
        return prefix + "-" + applicationId;
    }

    private String courseCode(String gradeApplied) {
        return emptyToNull(gradeApplied);
    }

    private String guardianEmail(ApplicationEntity application) {
        GuardianEntity guardian = application.getGuardian();
        String email = firstNonBlank(
            guardian == null ? null : guardian.getEmail(),
            guardian == null || guardian.getUser() == null ? null : guardian.getUser().getEmail(),
            application.getApplicantUser() == null ? null : application.getApplicantUser().getEmail());
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private String guardianAddress(ApplicationEntity application) {
        GuardianEntity guardian = application.getGuardian();
        String direct = guardian == null ? null : emptyToNull(guardian.getAddress());
        if (direct != null) return direct.trim();

        String relationship = lower(guardian == null ? null : guardian.getRelationship());
        if (relationship.contains("madre") || relationship.equals("mother")) {
            return parentAddress(application.getMother());
        }
        if (relationship.contains("padre") || relationship.equals("father")) {
            return parentAddress(application.getFather());
        }
        return firstNonBlank(parentAddress(application.getMother()), parentAddress(application.getFather()));
    }

    private static String parentAddress(ParentEntity parent) {
        return parent == null ? null : emptyToNull(parent.getAddress());
    }

    private static String normalizedName(String value) {
        String name = emptyToNull(value);
        return name == null ? null : name.trim().replaceAll("\\s+", " ");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String candidate = emptyToNull(value);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private LocalDateTime parseSchoolDate(String value) {
        if (!blank(value)) {
            try { return LocalDateTime.parse(value, SCHOOL_DATE_TIME); } catch (DateTimeParseException ignored) {}
            try { return LocalDateTime.parse(value); } catch (DateTimeParseException ignored) {}
        }
        return LocalDateTime.now(providerZone);
    }

    private static String studentName(StudentEntity student) {
        return (nullToEmpty(student.getFirstName()) + " " + nullToEmpty(student.getPaternalLastName()) + " " + nullToEmpty(student.getMaternalLastName())).trim();
    }

    private static List<String> safeList(List<String> values) { return values == null ? List.of() : values; }
    private static String lower(String value) { return nullToEmpty(value).trim().toLowerCase(Locale.ROOT); }
    private static String upper(String value) { return nullToEmpty(value).trim().toUpperCase(Locale.ROOT); }
    private static String emptyToNull(String value) { return blank(value) ? null : value.trim(); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
