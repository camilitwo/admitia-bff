package cl.mtn.admitiabff.service.reminder;

import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.service.reminder.ApplicationReminderRepository.ApplicationState;
import cl.mtn.admitiabff.service.reminder.ApplicationReminderRepository.Delivery;
import cl.mtn.admitiabff.util.TemplateUtils;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
@ConditionalOnProperty(prefix = "app.application-reminders", name = "enabled", havingValue = "true")
public class ApplicationReminderService {
    private static final Logger log = LoggerFactory.getLogger(ApplicationReminderService.class);
    private static final LocalTime SLOT_TIME = LocalTime.of(11, 0);
    private static final int[] RETRY_MINUTES = {5, 15, 30, 60, 120};

    private final ApplicationReminderRepository repository;
    private final EmailComposerService emailComposer;
    private final int academicYear;
    private final ZoneId zone;
    private final int batchSize;
    private final int leaseMinutes;
    private final int maxAttempts;
    private final String portalUrl;
    private final Clock clock;

    public ApplicationReminderService(ApplicationReminderRepository repository,
            EmailComposerService emailComposer,
            @Value("${app.application-reminders.academic-year:2027}") int academicYear,
            @Value("${app.application-reminders.zone:America/Santiago}") String zone,
            @Value("${app.application-reminders.batch-size:50}") int batchSize,
            @Value("${app.application-reminders.lease-minutes:10}") int leaseMinutes,
            @Value("${app.application-reminders.max-attempts:6}") int maxAttempts,
            @Value("${app.frontend.base-url}") String frontendBaseUrl) {
        this(repository, emailComposer, academicYear, ZoneId.of(zone), batchSize, leaseMinutes,
            maxAttempts, familyPortalUrl(frontendBaseUrl), Clock.systemUTC());
    }

    ApplicationReminderService(ApplicationReminderRepository repository, EmailComposerService emailComposer,
            int academicYear, ZoneId zone, int batchSize, int leaseMinutes, int maxAttempts,
            String portalUrl, Clock clock) {
        if (academicYear < 2027 || batchSize < 1 || leaseMinutes < 1 || maxAttempts < 1 || maxAttempts > 6) {
            throw new IllegalArgumentException("Configuración inválida de application-reminders");
        }
        this.repository = repository;
        this.emailComposer = emailComposer;
        this.academicYear = academicYear;
        this.zone = zone;
        this.batchSize = batchSize;
        this.leaseMinutes = leaseMinutes;
        this.maxAttempts = maxAttempts;
        this.portalUrl = portalUrl;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.application-reminders.cron:0 0 11 * * MON,THU}",
        zone = "${app.application-reminders.zone:America/Santiago}")
    public void scheduledSlot() {
        processCurrentSlot();
    }

    @Scheduled(fixedDelayString = "${app.application-reminders.dispatcher-delay-ms:60000}",
        initialDelayString = "30000")
    public void recoverAndRetry() {
        processCurrentSlot();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        processCurrentSlot();
    }

    void processCurrentSlot() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        if (!isReminderDay(now.getDayOfWeek()) || now.toLocalTime().isBefore(SLOT_TIME)) {
            return;
        }
        ZonedDateTime slot = now.toLocalDate().atTime(SLOT_TIME).atZone(zone);
        materialize(slot);
        dispatch(slot.toLocalDate());
    }

    void materialize(ZonedDateTime slot) {
        if (repository.isSlotMaterialized(slot.toInstant())) return;
        int pending = 0;
        int skipped = 0;
        for (ApplicationState state : repository.findActiveStates(academicYear)) {
            Decision decision = classify(state);
            if (decision.type() == null) continue;
            String reason = decision.reason();
            if (reason == null && !hasValidEmail(state.recipient())) reason = "NO_RECIPIENT";
            String status = reason == null ? "PENDING" : "SKIPPED";
            repository.insertDelivery(state, decision.type().name(), slot.toInstant(), slot.toLocalDate(), status, reason);
            if (reason == null) pending++; else skipped++;
        }
        repository.markSlotMaterialized(slot.toInstant(), slot.toLocalDate(), academicYear);
        log.info("Recordatorios materializados slot={} year={} pendingCandidates={} skippedCandidates={}",
            slot, academicYear, pending, skipped);
    }

    void dispatch(LocalDate today) {
        while (true) {
            int processed = 0;
            for (; processed < batchSize; processed++) {
                Optional<Delivery> claimed = repository.claimNext(today, maxAttempts, leaseMinutes);
                if (claimed.isEmpty()) return;
                deliver(claimed.get());
            }
            log.info("Lote de recordatorios procesado date={} size={}; buscando siguiente lote",
                today, processed);
        }
    }

    private void deliver(Delivery delivery) {
        ApplicationState state = repository.findState(delivery.applicationId(), academicYear).orElse(null);
        if (state == null) {
            repository.markSkipped(delivery.id(), null, "APPLICATION_NOT_FOUND");
            return;
        }
        Decision current = classify(state);
        if (current.reason() != null || current.type() == null
                || !current.type().name().equals(delivery.reminderType())) {
            repository.markSkipped(delivery.id(), state.recipient(),
                current.reason() == null ? "NO_LONGER_ELIGIBLE" : current.reason());
            return;
        }
        if (!hasValidEmail(state.recipient())) {
            repository.markSkipped(delivery.id(), state.recipient(), "NO_RECIPIENT");
            return;
        }

        String key = "application-reminder/" + delivery.scheduledDate() + "/"
            + delivery.applicationId() + "/" + delivery.reminderType();
        Map<String, Object> data = Map.of(
            "applicationId", state.applicationId(),
            "guardianName", escape(state.guardianName()),
            "studentName", escape(state.studentName()),
            "gradeApplied", escape(state.gradeApplied()),
            "academicYear", state.academicYear(),
            "portalUrl", escape(portalUrl));
        try {
            Map<String, Object> result = emailComposer.send(EmailRequestDTO.builder()
                .template(TemplateUtils.generateTemplate(current.type().name(), data))
                .to(state.recipient().trim())
                .subject(current.type().getDefaultSubject())
                .recipientType("APPLICATION")
                .recipientId(state.applicationId())
                .data(data)
                .sensitive(false)
                .templateName(current.type().name())
                .idempotencyKey(key)
                .build());
            repository.markSent(delivery.id(), state.recipient().trim(), providerMessageId(result));
            log.info("Recordatorio enviado deliveryId={} applicationId={} type={}",
                delivery.id(), delivery.applicationId(), delivery.reminderType());
        } catch (Exception exception) {
            handleFailure(delivery, state.recipient(), exception);
        }
    }

    private void handleFailure(Delivery delivery, String recipient, Exception exception) {
        String error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        int retryIndex = Math.min(delivery.attempts() - 1, RETRY_MINUTES.length - 1);
        Instant nextAttempt = delivery.attempts() >= maxAttempts ? null
            : clock.instant().plus(Duration.ofMinutes(RETRY_MINUTES[retryIndex]));
        repository.markFailed(delivery.id(), recipient, error.substring(0, Math.min(error.length(), 2000)), nextAttempt);
        log.error("Falló recordatorio deliveryId={} applicationId={} attempt={}",
            delivery.id(), delivery.applicationId(), delivery.attempts(), exception);
    }

    static Decision classify(ApplicationState state) {
        if (!state.active()) return new Decision(null, "NOT_ELIGIBLE");
        if (state.externalPaid() && !state.paymentConfirmed()) {
            return new Decision(EmailTemplate.PAYMENT_REMINDER, "PAYMENT_STATUS_INCONSISTENT");
        }
        if (state.paymentRequired() && !state.paymentConfirmed()) {
            return new Decision(EmailTemplate.PAYMENT_REMINDER, null);
        }
        if (!state.formSubmitted()) {
            return new Decision(EmailTemplate.FAMILY_REGISTRATION_REMINDER, null);
        }
        return new Decision(null, null);
    }

    static boolean isReminderDay(DayOfWeek day) {
        return day == DayOfWeek.MONDAY || day == DayOfWeek.THURSDAY;
    }

    private static boolean hasValidEmail(String email) {
        if (email == null) return false;
        String value = email.trim();
        int at = value.indexOf('@');
        return at > 0 && at < value.length() - 1 && !value.contains(" ");
    }

    private static String familyPortalUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("APP_FRONTEND_BASE_URL requerido");
        return baseUrl.trim().replaceAll("/+$", "") + "/familia";
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    private static String providerMessageId(Map<String, Object> result) {
        Object data = result == null ? null : result.get("data");
        if (!(data instanceof Map<?, ?> values)) return null;
        Object messageId = values.get("providerMessageId");
        return messageId == null ? null : String.valueOf(messageId);
    }

    record Decision(EmailTemplate type, String reason) {}
}
