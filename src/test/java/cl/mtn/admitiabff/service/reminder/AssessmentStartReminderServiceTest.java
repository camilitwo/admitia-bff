package cl.mtn.admitiabff.service.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.service.reminder.ApplicationReminderRepository.ApplicationState;
import cl.mtn.admitiabff.service.reminder.AssessmentStartReminderRepository.Delivery;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AssessmentStartReminderServiceTest {
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    @Test
    void requiresActivePaidAndSubmittedApplication() {
        assertThat(AssessmentStartReminderService.eligible(state(true, true, true, true))).isTrue();
        assertThat(AssessmentStartReminderService.eligible(state(true, false, false, true))).isTrue();
        assertThat(AssessmentStartReminderService.eligible(state(true, true, false, true))).isFalse();
        assertThat(AssessmentStartReminderService.eligible(state(true, true, true, false))).isFalse();
        assertThat(AssessmentStartReminderService.eligible(state(false, true, true, true))).isFalse();
    }

    @Test
    void doesNothingBeforeFirstSlot() {
        ApplicationReminderRepository applications = Mockito.mock(ApplicationReminderRepository.class);
        AssessmentStartReminderRepository deliveries = Mockito.mock(AssessmentStartReminderRepository.class);
        EmailComposerService composer = Mockito.mock(EmailComposerService.class);
        AssessmentStartReminderService service = service(applications, deliveries, composer,
            Instant.parse("2026-09-01T20:59:59Z"));

        service.processDueSlots();

        verifyNoInteractions(applications, deliveries, composer);
    }

    @Test
    void materializesPreviousDaySlotAtSeventeenInChile() {
        ApplicationReminderRepository applications = Mockito.mock(ApplicationReminderRepository.class);
        AssessmentStartReminderRepository deliveries = Mockito.mock(AssessmentStartReminderRepository.class);
        EmailComposerService composer = Mockito.mock(EmailComposerService.class);
        AssessmentStartReminderService service = service(applications, deliveries, composer,
            Instant.parse("2026-09-01T21:00:00Z"));
        ApplicationState candidate = state(true, true, true, true);
        when(applications.findActiveStates(2027)).thenReturn(List.of(candidate));
        when(deliveries.claimNext(Instant.parse("2026-09-01T21:00:00Z"), 6, 10))
            .thenReturn(Optional.empty());

        service.processDueSlots();

        verify(deliveries).insertDelivery(10L, "familia@example.cl",
            Instant.parse("2026-09-01T21:00:00Z"), "PENDING", null);
        verify(deliveries).markSlotMaterialized(Instant.parse("2026-09-01T21:00:00Z"),
            LocalDate.of(2026, 9, 2), 2027);
    }

    @Test
    void revalidatesAndSendsSameDayMessageAtSeven() {
        ApplicationReminderRepository applications = Mockito.mock(ApplicationReminderRepository.class);
        AssessmentStartReminderRepository deliveries = Mockito.mock(AssessmentStartReminderRepository.class);
        EmailComposerService composer = Mockito.mock(EmailComposerService.class);
        AssessmentStartReminderService service = service(applications, deliveries, composer,
            Instant.parse("2026-09-02T11:00:00Z"));
        Delivery delivery = new Delivery(4L, 10L, Instant.parse("2026-09-02T11:00:00Z"), 1);
        when(deliveries.claimNext(Instant.parse("2026-09-02T11:00:00Z"), 6, 10))
            .thenReturn(Optional.of(delivery), Optional.empty());
        when(applications.findState(10L, 2027)).thenReturn(Optional.of(state(true, true, true, true)));
        when(composer.send(any())).thenReturn(Map.of("success", true,
            "data", Map.of("providerMessageId", "resend-123")));

        service.dispatch(Instant.parse("2026-09-02T11:00:00Z"));

        ArgumentCaptor<EmailRequestDTO> request = ArgumentCaptor.forClass(EmailRequestDTO.class);
        verify(composer).send(request.capture());
        assertThat(request.getValue().subject)
            .isEqualTo("Recordatorio: hoy comienzan las pruebas de admisión");
        assertThat(request.getValue().template)
            .contains("hoy", "miércoles 2 de septiembre", "Ana P&eacute;rez")
            .doesNotContain("{{");
        assertThat(request.getValue().idempotencyKey)
            .isEqualTo("assessment-start-reminder/2026-09-02T11:00:00Z/10");
        verify(deliveries).markSent(4L, "familia@example.cl", "resend-123");
    }

    @Test
    void skipsWhenEligibilityChangesBeforeDispatch() {
        ApplicationReminderRepository applications = Mockito.mock(ApplicationReminderRepository.class);
        AssessmentStartReminderRepository deliveries = Mockito.mock(AssessmentStartReminderRepository.class);
        EmailComposerService composer = Mockito.mock(EmailComposerService.class);
        AssessmentStartReminderService service = service(applications, deliveries, composer,
            Instant.parse("2026-09-01T21:00:00Z"));
        Delivery delivery = new Delivery(4L, 10L, Instant.parse("2026-09-01T21:00:00Z"), 1);
        when(deliveries.claimNext(any(), eq(6), eq(10)))
            .thenReturn(Optional.of(delivery), Optional.empty());
        when(applications.findState(10L, 2027)).thenReturn(Optional.of(state(true, true, true, false)));

        service.dispatch(Instant.parse("2026-09-01T21:00:00Z"));

        verify(deliveries).markSkipped(4L, "familia@example.cl", "NO_LONGER_ELIGIBLE");
        verify(composer, never()).send(any());
    }

    private static AssessmentStartReminderService service(ApplicationReminderRepository applications,
            AssessmentStartReminderRepository deliveries, EmailComposerService composer, Instant instant) {
        return new AssessmentStartReminderService(applications, deliveries, composer,
            LocalDate.of(2026, 9, 2), 2027, CHILE, 50, 10, 6, 24,
            Clock.fixed(instant, ZoneId.of("UTC")));
    }

    private static ApplicationState state(boolean active, boolean paymentRequired,
            boolean paid, boolean formSubmitted) {
        return new ApplicationState(10L, active, paymentRequired, paid, false, false,
            formSubmitted, "familia@example.cl", "Camila Pérez", "Ana Pérez", "1° Básico", 2027);
    }
}
