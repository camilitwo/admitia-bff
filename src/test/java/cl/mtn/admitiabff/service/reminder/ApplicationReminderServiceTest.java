package cl.mtn.admitiabff.service.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.service.reminder.ApplicationReminderRepository.ApplicationState;
import cl.mtn.admitiabff.service.reminder.ApplicationReminderRepository.Delivery;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ApplicationReminderServiceTest {
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    @ParameterizedTest
    @MethodSource("classificationCases")
    void appliesExclusiveReminderPrecedence(ApplicationState state, EmailTemplate expectedType,
            String expectedReason) {
        var decision = ApplicationReminderService.classify(state);
        assertThat(decision.type()).isEqualTo(expectedType);
        assertThat(decision.reason()).isEqualTo(expectedReason);
    }

    static Stream<Arguments> classificationCases() {
        return Stream.of(
            Arguments.of(state(true, true, false, false, false, false),
                EmailTemplate.PAYMENT_REMINDER, null),
            Arguments.of(state(true, true, true, false, false, false),
                EmailTemplate.FAMILY_REGISTRATION_REMINDER, null),
            Arguments.of(state(true, false, false, false, false, false),
                EmailTemplate.FAMILY_REGISTRATION_REMINDER, null),
            Arguments.of(state(true, true, false, true, false, false),
                EmailTemplate.FAMILY_REGISTRATION_REMINDER, null),
            Arguments.of(state(true, true, true, false, false, true), null, null),
            Arguments.of(state(true, true, false, false, true, false),
                EmailTemplate.PAYMENT_REMINDER, "PAYMENT_STATUS_INCONSISTENT"),
            Arguments.of(state(false, true, false, false, false, false), null, "NOT_ELIGIBLE")
        );
    }

    @Test
    void mondayAtElevenMaterializesAndDispatchesUsingChileTime() {
        ApplicationReminderRepository repository = Mockito.mock(ApplicationReminderRepository.class);
        EmailComposerService composer = Mockito.mock(EmailComposerService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T15:00:00Z"), ZoneId.of("UTC"));
        ApplicationReminderService service = service(repository, composer, clock);
        ApplicationState candidate = state(true, true, false, false, false, false);
        when(repository.findActiveStates(2027)).thenReturn(List.of(candidate));
        when(repository.claimNext(LocalDate.of(2026, 8, 24), 6, 10)).thenReturn(Optional.empty());

        service.processCurrentSlot();

        verify(repository).insertDelivery(eq(candidate), eq("PAYMENT_REMINDER"),
            eq(Instant.parse("2026-08-24T15:00:00Z")), eq(LocalDate.of(2026, 8, 24)),
            eq("PENDING"), eq(null));
        verify(repository).markSlotMaterialized(Instant.parse("2026-08-24T15:00:00Z"),
            LocalDate.of(2026, 8, 24), 2027);
        verify(repository).claimNext(LocalDate.of(2026, 8, 24), 6, 10);
    }

    @Test
    void summerDstStillUsesElevenOclockInChile() {
        ApplicationReminderRepository repository = Mockito.mock(ApplicationReminderRepository.class);
        EmailComposerService composer = Mockito.mock(EmailComposerService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-01-05T14:00:00Z"), ZoneId.of("UTC"));
        ApplicationReminderService service = service(repository, composer, clock);
        when(repository.findActiveStates(2027)).thenReturn(List.of());
        when(repository.claimNext(LocalDate.of(2026, 1, 5), 6, 10)).thenReturn(Optional.empty());

        service.processCurrentSlot();

        verify(repository).findActiveStates(2027);
        verify(repository).claimNext(LocalDate.of(2026, 1, 5), 6, 10);
    }

    @Test
    void doesNothingOutsideMondayOrThursday() {
        ApplicationReminderRepository repository = Mockito.mock(ApplicationReminderRepository.class);
        EmailComposerService composer = Mockito.mock(EmailComposerService.class);
        ApplicationReminderService service = service(repository, composer,
            Clock.fixed(Instant.parse("2026-08-25T15:00:00Z"), ZoneId.of("UTC")));

        service.processCurrentSlot();

        verifyNoInteractions(repository, composer);
    }

    @Test
    void revalidatesAndSendsRenderedTemplateWithStableIdempotencyKey() {
        ApplicationReminderRepository repository = Mockito.mock(ApplicationReminderRepository.class);
        EmailComposerService composer = Mockito.mock(EmailComposerService.class);
        ApplicationReminderService service = service(repository, composer,
            Clock.fixed(Instant.parse("2026-08-24T15:00:00Z"), ZoneId.of("UTC")));
        Delivery delivery = new Delivery(9L, 10L, "PAYMENT_REMINDER",
            Instant.parse("2026-08-24T15:00:00Z"), LocalDate.of(2026, 8, 24), 1);
        ApplicationState current = state(true, true, false, false, false, false);
        when(repository.claimNext(LocalDate.of(2026, 8, 24), 6, 10))
            .thenReturn(Optional.of(delivery), Optional.empty());
        when(repository.findState(10L, 2027)).thenReturn(Optional.of(current));
        when(composer.send(any())).thenReturn(Map.of("success", true,
            "data", Map.of("providerMessageId", "resend-123")));

        service.dispatch(LocalDate.of(2026, 8, 24));

        ArgumentCaptor<EmailRequestDTO> request = ArgumentCaptor.forClass(EmailRequestDTO.class);
        verify(composer).send(request.capture());
        assertThat(request.getValue().subject).isEqualTo("Recordatorio: pago pendiente de tu postulación");
        assertThat(request.getValue().idempotencyKey)
            .isEqualTo("application-reminder/2026-08-24/10/PAYMENT_REMINDER");
        assertThat(request.getValue().template).contains("Pago pendiente", "Ana P&eacute;rez", "/familia");
        assertThat(request.getValue().template).doesNotContain("{{");
        verify(repository).markSent(9L, "familia@example.cl", "resend-123");
    }

    @Test
    void skipsPaymentReminderWhenPaymentWasReconciledBeforeDispatch() {
        ApplicationReminderRepository repository = Mockito.mock(ApplicationReminderRepository.class);
        EmailComposerService composer = Mockito.mock(EmailComposerService.class);
        ApplicationReminderService service = service(repository, composer, Clock.systemUTC());
        Delivery delivery = new Delivery(9L, 10L, "PAYMENT_REMINDER", Instant.now(),
            LocalDate.of(2026, 8, 24), 1);
        when(repository.claimNext(LocalDate.of(2026, 8, 24), 6, 10))
            .thenReturn(Optional.of(delivery), Optional.empty());
        when(repository.findState(10L, 2027))
            .thenReturn(Optional.of(state(true, true, true, false, false, false)));

        service.dispatch(LocalDate.of(2026, 8, 24));

        verify(repository).markSkipped(9L, "familia@example.cl", "NO_LONGER_ELIGIBLE");
        verify(composer, never()).send(any());
    }

    @Test
    void transientFailureSchedulesFirstRetryFiveMinutesLater() {
        ApplicationReminderRepository repository = Mockito.mock(ApplicationReminderRepository.class);
        EmailComposerService composer = Mockito.mock(EmailComposerService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T15:00:00Z"), ZoneId.of("UTC"));
        ApplicationReminderService service = service(repository, composer, clock);
        Delivery delivery = new Delivery(9L, 10L, "PAYMENT_REMINDER", clock.instant(),
            LocalDate.of(2026, 8, 24), 1);
        when(repository.claimNext(LocalDate.of(2026, 8, 24), 6, 10))
            .thenReturn(Optional.of(delivery), Optional.empty());
        when(repository.findState(10L, 2027)).thenReturn(Optional.of(state(true, true, false, false, false, false)));
        when(composer.send(any())).thenThrow(new RuntimeException("Resend temporalmente no disponible"));

        service.dispatch(LocalDate.of(2026, 8, 24));

        verify(repository).markFailed(9L, "familia@example.cl", "Resend temporalmente no disponible",
            Instant.parse("2026-08-24T15:05:00Z"));
    }

    @Test
    void sixthFailedAttemptIsTerminal() {
        ApplicationReminderRepository repository = Mockito.mock(ApplicationReminderRepository.class);
        EmailComposerService composer = Mockito.mock(EmailComposerService.class);
        ApplicationReminderService service = service(repository, composer, Clock.systemUTC());
        Delivery delivery = new Delivery(9L, 10L, "PAYMENT_REMINDER", Instant.now(),
            LocalDate.of(2026, 8, 24), 6);
        when(repository.claimNext(LocalDate.of(2026, 8, 24), 6, 10))
            .thenReturn(Optional.of(delivery), Optional.empty());
        when(repository.findState(10L, 2027)).thenReturn(Optional.of(state(true, true, false, false, false, false)));
        when(composer.send(any())).thenThrow(new RuntimeException("Fallo definitivo"));

        service.dispatch(LocalDate.of(2026, 8, 24));

        verify(repository).markFailed(eq(9L), eq("familia@example.cl"), eq("Fallo definitivo"), isNull());
    }

    private static ApplicationReminderService service(ApplicationReminderRepository repository,
            EmailComposerService composer, Clock clock) {
        return new ApplicationReminderService(repository, composer, 2027, CHILE, 50, 10, 6,
            "https://admitia.cl/familia", clock);
    }

    private static ApplicationState state(boolean active, boolean required, boolean appPaid,
            boolean paymentPaid, boolean externalPaid, boolean formSubmitted) {
        return new ApplicationState(10L, active, required, appPaid, paymentPaid, externalPaid,
            formSubmitted, "familia@example.cl", "Camila Pérez", "Ana Pérez", "1° Básico", 2027);
    }
}
