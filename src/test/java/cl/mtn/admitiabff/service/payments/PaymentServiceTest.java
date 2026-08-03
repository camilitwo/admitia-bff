package cl.mtn.admitiabff.service.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.PaymentStatus;
import cl.mtn.admitiabff.domain.payment.ApplicationSchoolSyncEntity;
import cl.mtn.admitiabff.domain.payment.PaymentEntity;
import cl.mtn.admitiabff.domain.person.GuardianEntity;
import cl.mtn.admitiabff.domain.person.ParentEntity;
import cl.mtn.admitiabff.domain.student.StudentEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.ApplicationSchoolSyncRepository;
import cl.mtn.admitiabff.repository.PaymentEventRepository;
import cl.mtn.admitiabff.repository.PaymentRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.AdmissionCycleGuard;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.AdmissionResponse;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.AdmissionRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeResponse;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeStatusResponse;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.StudentResponse;
import cl.mtn.admitiabff.util.JsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PaymentServiceTest {
    private ApplicationRepository applications;
    private UserRepository users;
    private PaymentRepository payments;
    private PaymentEventRepository events;
    private ApplicationSchoolSyncRepository syncs;
    private MtnAdmissionGateway client;
    private PaymentService service;
    private ApplicationEntity application;
    private UserEntity user;
    private final AtomicReference<PaymentEntity> storedPayment = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        applications = mock(ApplicationRepository.class);
        users = mock(UserRepository.class);
        payments = mock(PaymentRepository.class);
        events = mock(PaymentEventRepository.class);
        syncs = mock(ApplicationSchoolSyncRepository.class);
        client = mock(MtnAdmissionGateway.class);
        AdmissionCycleGuard admissionCycleGuard = mock(AdmissionCycleGuard.class);
        service = new PaymentService(applications, users, payments, events, syncs, client, properties(),
            new JsonSupport(new ObjectMapper()), admissionCycleGuard);

        user = new UserEntity();
        user.setId(7L);
        user.setFirstName("Juan");
        user.setLastName("Perez");
        user.setEmail("juan@example.invalid");

        GuardianEntity guardian = new GuardianEntity();
        guardian.setId(10L);
        guardian.setFullName("  Juan   Perez  ");
        guardian.setRut("12.345.678-5");
        guardian.setEmail(" JUAN@EXAMPLE.INVALID ");
        guardian.setPhone("56911111111");
        guardian.setRelationship("madre");

        ParentEntity mother = new ParentEntity();
        mother.setFullName("Juan Perez");
        mother.setAddress("Calle QA 123");

        StudentEntity student = new StudentEntity();
        student.setId(11L);
        student.setFirstName("Ana");
        student.setPaternalLastName("Perez");
        student.setRut("11.111.111-1");
        student.setGradeApplied("1_BASICO");

        application = new ApplicationEntity();
        application.setId(20L);
        application.setApplicantUser(user);
        application.setGuardian(guardian);
        application.setMother(mother);
        application.setStudent(student);
        application.setPaymentRequired(true);
        application.setPaymentStatus(PaymentStatus.UNPAID);
        application.setSubmissionDate(LocalDateTime.of(2026, 7, 21, 10, 0));

        when(applications.findActiveByIdForUpdate(20L)).thenReturn(Optional.of(application));
        when(applications.findActiveById(20L)).thenReturn(Optional.of(application));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(payments.findByIdempotencyKey("ADMITIA-20")).thenAnswer(invocation -> Optional.ofNullable(storedPayment.get()));
        when(payments.findFirstByApplicationIdOrderByCreatedAtDesc(20L)).thenAnswer(invocation -> Optional.ofNullable(storedPayment.get()));
        when(payments.save(any(PaymentEntity.class))).thenAnswer(invocation -> {
            PaymentEntity value = invocation.getArgument(0);
            if (value.getId() == null) value.setId(100L);
            storedPayment.set(value);
            return value;
        });
        when(syncs.findByApplicationId(20L)).thenReturn(Optional.empty());
        when(syncs.save(any(ApplicationSchoolSyncEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsOneInstitutionalChargeAndReusesItOnRetry() {
        when(client.synchronizeAdmission(any())).thenReturn(successfulAdmission());
        when(client.createCharge(any())).thenReturn(successfulCharge("https://pay.example/301"));
        when(client.chargeStatus(301L)).thenReturn(pendingStatus());

        Map<String, Object> first = service.checkout(20L, 7L);
        Map<String, Object> second = service.checkout(20L, 7L);

        assertEquals("https://pay.example/301", data(first).get("checkoutUrl"));
        assertEquals("https://pay.example/301", data(second).get("checkoutUrl"));
        assertEquals(301L, storedPayment.get().getInstitutionalChargeId());
        ArgumentCaptor<AdmissionRequest> admissionRequest = ArgumentCaptor.forClass(AdmissionRequest.class);
        ArgumentCaptor<ChargeRequest> chargeRequest = ArgumentCaptor.forClass(ChargeRequest.class);
        verify(client, times(1)).synchronizeAdmission(admissionRequest.capture());
        verify(client, times(1)).createCharge(chargeRequest.capture());
        assertEquals("12345678", admissionRequest.getValue().value());
        assertEquals("11111111", admissionRequest.getValue().alumnos().get(0).value());
        assertEquals("1_BASICO", admissionRequest.getValue().alumnos().get(0).codCurso());
        assertEquals("Juan Perez", admissionRequest.getValue().name());
        assertEquals("juan@example.invalid", admissionRequest.getValue().email());
        assertEquals("Calle QA 123", admissionRequest.getValue().address1());
        assertEquals("1_BASICO", chargeRequest.getValue().studentCourse());
        assertEquals("Glosa configurada desde Railway", chargeRequest.getValue().concept());
        verify(client, times(1)).chargeStatus(301L);
    }

    @Test
    void rejectsChargeAssociatedWithAnotherInstitutionalStudent() {
        when(client.synchronizeAdmission(any())).thenReturn(successfulAdmission());
        when(client.createCharge(any())).thenReturn(new ChargeResponse(true, "OK", "ok", List.of(), List.of(),
            301L, "inv_301", "https://pay.example/301", new BigDecimal("50000"), "CLP", "2026-08-15",
            "PENDIENTE", 105L, 999L, "ADMITIA-20"));

        PaymentIntegrationException error = assertThrows(PaymentIntegrationException.class,
            () -> service.checkout(20L, 7L));

        assertEquals("SCHOOL_VALIDATION_ERROR", error.code());
        assertEquals(PaymentStatus.FAILED, storedPayment.get().getStatus());
        assertNull(storedPayment.get().getInstitutionalChargeId());
    }

    @Test
    void doesNotCreateChargeWhenAdmissionDidNotConfirmTokuCustomerAndSubscription() {
        when(client.synchronizeAdmission(any())).thenReturn(partialAdmissionWithoutTokuCustomer());

        PaymentIntegrationException error = assertThrows(PaymentIntegrationException.class,
            () -> service.checkout(20L, 7L));

        assertEquals("SCHOOL_VALIDATION_ERROR", error.code());
        assertEquals(PaymentStatus.FAILED, storedPayment.get().getStatus());
        verify(client, never()).createCharge(any());
        ArgumentCaptor<ApplicationSchoolSyncEntity> sync = ArgumentCaptor.forClass(ApplicationSchoolSyncEntity.class);
        verify(syncs, times(3)).save(sync.capture());
        assertEquals("FAILED", sync.getValue().getSyncStatus());
    }

    @Test
    void paidReconciliationUnlocksOnlyItsApplication() {
        PaymentEntity payment = pendingPayment();
        storedPayment.set(payment);
        application.setPaymentStatus(PaymentStatus.PAYMENT_PENDING);
        when(client.chargeStatus(301L)).thenReturn(new ChargeStatusResponse(true, 301L, "inv_301", true, "PAGADO",
            new BigDecimal("50000"), "CLP", "2026-08-15", "2026-08-10 14:32", new BigDecimal("50000"),
            "trx_1", "voucher_1", "transfer", null));

        Map<String, Object> response = service.status(20L, 7L);

        assertEquals(PaymentStatus.PAID, application.getPaymentStatus());
        assertEquals(PaymentStatus.PAID, payment.getStatus());
        assertTrue((Boolean) data(response).get("canFillComplementaryForm"));
        assertNotNull(application.getPaidAt());
    }

    @Test
    void rejectsSuccessfulChargeWithoutPaymentLink() {
        when(client.synchronizeAdmission(any())).thenReturn(successfulAdmission());
        when(client.createCharge(any())).thenReturn(successfulCharge(""));

        PaymentIntegrationException error = assertThrows(PaymentIntegrationException.class, () -> service.checkout(20L, 7L));

        assertEquals("SCHOOL_PAYMENT_LINK_MISSING", error.code());
        assertEquals(PaymentStatus.FAILED, application.getPaymentStatus());
    }

    @Test
    void homeReconciliationIsBestEffortWhenSchoolIsUnavailable() {
        PaymentEntity payment = pendingPayment();
        storedPayment.set(payment);
        when(payments.findByGuardianUserIdAndStatusAndInstitutionalChargeIdIsNotNull(7L, PaymentStatus.PAYMENT_PENDING))
            .thenReturn(List.of(payment));
        when(client.chargeStatus(301L)).thenThrow(PaymentIntegrationException.unavailable("offline"));

        service.reconcilePendingForUserBestEffort(7L);

        assertEquals(PaymentStatus.PAYMENT_PENDING, payment.getStatus());
        verify(applications, never()).save(application);
    }

    @Test
    void keepsCreatedChargePendingWhenImmediateReconciliationFails() {
        when(client.synchronizeAdmission(any())).thenReturn(successfulAdmission());
        when(client.createCharge(any())).thenReturn(new ChargeResponse(true, "YA_EXISTIA", "ok", List.of(), List.of(),
            301L, "inv_301", "", new BigDecimal("50000"), "CLP", "2026-08-15", "PAGADO",
            105L, 205L, "ADMITIA-20"));
        when(client.chargeStatus(301L)).thenThrow(PaymentIntegrationException.unavailable("offline"));

        assertThrows(PaymentIntegrationException.class, () -> service.checkout(20L, 7L));

        assertEquals(PaymentStatus.PAYMENT_PENDING, application.getPaymentStatus());
        assertEquals(PaymentStatus.PAYMENT_PENDING, storedPayment.get().getStatus());
        assertEquals(301L, storedPayment.get().getInstitutionalChargeId());
    }

    private PaymentEntity pendingPayment() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(100L);
        payment.setApplication(application);
        payment.setGuardianUser(user);
        payment.setProvider("MTN_ADMISSION_API");
        payment.setIdempotencyKey("ADMITIA-20");
        payment.setAmount(new BigDecimal("50000"));
        payment.setCurrency("CLP");
        payment.setStatus(PaymentStatus.PAYMENT_PENDING);
        payment.setInstitutionalChargeId(301L);
        payment.setCheckoutUrl("https://pay.example/301");
        return payment;
    }

    private AdmissionResponse successfulAdmission() {
        return new AdmissionResponse(true, "OK", "ok", List.of(), List.of(), 105L, 106L,
            "12.345.678-5", "creado", "cus_1", "creado",
            List.of(new StudentResponse("11.111.111-1", "Ana Perez", 205L, "creado", "sub_1", "creado", null)));
    }

    private AdmissionResponse partialAdmissionWithoutTokuCustomer() {
        return new AdmissionResponse(true, "PARCIAL", "procesado con advertencias", List.of(),
            List.of("No se pudo crear el cliente en Toku (HTTP 422)."), 105L, 106L,
            "12.345.678-5", "ya_existia", null, "error: HTTP 422",
            List.of(new StudentResponse("11.111.111-1", "Ana Perez", 205L, "ya_existia", null, "sin_customer", null)));
    }

    private ChargeResponse successfulCharge(String link) {
        return new ChargeResponse(true, "OK", "ok", List.of(), List.of(), 301L, "inv_301", link,
            new BigDecimal("50000"), "CLP", "2026-08-15", "PENDIENTE", 105L, 205L, "ADMITIA-20");
    }

    private ChargeStatusResponse pendingStatus() {
        return new ChargeStatusResponse(true, 301L, "inv_301", false, "PENDIENTE", new BigDecimal("50000"),
            "CLP", "2026-08-15", null, null, null, null, null, "https://pay.example/301");
    }

    private MtnAdmissionProperties properties() {
        return new MtnAdmissionProperties(true, "https://school.example/api", "/auth/token", "/admision/apoderados", "/admision/cobros",
            "ADMISION", "secret", MtnAdmissionProperties.ClientAuthMethod.BASIC, false, Duration.ofSeconds(2), Duration.ofSeconds(2),
            new BigDecimal("50000"), "Glosa configurada desde Railway", "CLP", 3, "ADMITIA", "Santiago", "America/Santiago");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Map<String, Object> response) {
        return (Map<String, Object>) response.get("data");
    }
}
