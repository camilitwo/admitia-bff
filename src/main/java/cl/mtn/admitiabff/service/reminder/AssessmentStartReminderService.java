package cl.mtn.admitiabff.service.reminder;

import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.service.reminder.ApplicationReminderRepository.ApplicationState;
import cl.mtn.admitiabff.service.reminder.AssessmentStartReminderRepository.Delivery;
import cl.mtn.admitiabff.util.TemplateUtils;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
@ConditionalOnProperty(prefix = "app.assessment-start-reminder", name = "enabled", havingValue = "true")
public class AssessmentStartReminderService {
    private static final Logger log = LoggerFactory.getLogger(AssessmentStartReminderService.class);
    private static final LocalTime PREVIOUS_DAY_TIME = LocalTime.of(17, 0);
    private static final LocalTime SAME_DAY_TIME = LocalTime.of(7, 0);
    private static final int[] RETRY_MINUTES = {5, 15, 30, 60, 120};
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
        .ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es-CL"));

    private final ApplicationReminderRepository applications;
    private final AssessmentStartReminderRepository deliveries;
    private final EmailComposerService emailComposer;
    private final LocalDate assessmentDate;
    private final int academicYear;
    private final ZoneId zone;
    private final int batchSize;
    private final int leaseMinutes;
    private final int maxAttempts;
    private final int catchUpWindowHours;
    private final Clock clock;

    @Autowired
    public AssessmentStartReminderService(ApplicationReminderRepository applications,
            AssessmentStartReminderRepository deliveries, EmailComposerService emailComposer,
            @Value("${app.assessment-start-reminder.assessment-date}") String assessmentDate,
            @Value("${app.assessment-start-reminder.academic-year:2027}") int academicYear,
            @Value("${app.assessment-start-reminder.zone:America/Santiago}") String zone,
            @Value("${app.assessment-start-reminder.batch-size:50}") int batchSize,
            @Value("${app.assessment-start-reminder.lease-minutes:10}") int leaseMinutes,
            @Value("${app.assessment-start-reminder.max-attempts:6}") int maxAttempts,
            @Value("${app.assessment-start-reminder.catch-up-window-hours:24}") int catchUpWindowHours) {
        this(applications, deliveries, emailComposer, LocalDate.parse(assessmentDate), academicYear,
            ZoneId.of(zone), batchSize, leaseMinutes, maxAttempts, catchUpWindowHours, Clock.systemUTC());
    }

    AssessmentStartReminderService(ApplicationReminderRepository applications,
            AssessmentStartReminderRepository deliveries, EmailComposerService emailComposer,
            LocalDate assessmentDate, int academicYear, ZoneId zone, int batchSize,
            int leaseMinutes, int maxAttempts, int catchUpWindowHours, Clock clock) {
        if (batchSize < 1 || leaseMinutes < 1 || maxAttempts < 1 || maxAttempts > 6
                || catchUpWindowHours < 1) {
            throw new IllegalArgumentException("Configuración inválida de assessment-start-reminder");
        }
        this.applications = applications;
        this.deliveries = deliveries;
        this.emailComposer = emailComposer;
        this.assessmentDate = assessmentDate;
        this.academicYear = academicYear;
        this.zone = zone;
        this.batchSize = batchSize;
        this.leaseMinutes = leaseMinutes;
        this.maxAttempts = maxAttempts;
        this.catchUpWindowHours = catchUpWindowHours;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.assessment-start-reminder.dispatcher-delay-ms:30000}",
        initialDelayString = "10000")
    public void scheduledDispatch() {
        processDueSlots();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        processDueSlots();
    }

    void processDueSlots() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        List<ZonedDateTime> slots = List.of(
            assessmentDate.minusDays(1).atTime(PREVIOUS_DAY_TIME).atZone(zone),
            assessmentDate.atTime(SAME_DAY_TIME).atZone(zone));
        boolean campaignOpen = false;
        for (ZonedDateTime slot : slots) {
            if (!now.isBefore(slot) && now.isBefore(slot.plusHours(catchUpWindowHours))) {
                materialize(slot);
                campaignOpen = true;
            }
        }
        if (campaignOpen) dispatch(now.toInstant());
    }

    void materialize(ZonedDateTime slot) {
        if (deliveries.isSlotMaterialized(slot.toInstant())) return;
        int pending = 0;
        int skipped = 0;
        for (ApplicationState state : applications.findActiveStates(academicYear)) {
            if (!eligible(state)) continue;
            String reason = hasValidEmail(state.recipient()) ? null : "NO_RECIPIENT";
            deliveries.insertDelivery(state.applicationId(), state.recipient(), slot.toInstant(),
                reason == null ? "PENDING" : "SKIPPED", reason);
            if (reason == null) pending++; else skipped++;
        }
        deliveries.markSlotMaterialized(slot.toInstant(), assessmentDate, academicYear);
        log.info("Campaña inicio de pruebas materializada slot={} year={} pending={} skipped={}",
            slot, academicYear, pending, skipped);
    }

    void dispatch(Instant now) {
        while (true) {
            int processed = 0;
            for (; processed < batchSize; processed++) {
                Optional<Delivery> claimed = deliveries.claimNext(now, maxAttempts, leaseMinutes);
                if (claimed.isEmpty()) return;
                deliver(claimed.get());
            }
            log.info("Lote de campaña inicio de pruebas procesado size={}", processed);
        }
    }

    private void deliver(Delivery delivery) {
        ApplicationState state = applications.findState(delivery.applicationId(), academicYear).orElse(null);
        if (state == null) {
            deliveries.markSkipped(delivery.id(), null, "APPLICATION_NOT_FOUND");
            return;
        }
        if (!eligible(state)) {
            deliveries.markSkipped(delivery.id(), state.recipient(), "NO_LONGER_ELIGIBLE");
            return;
        }
        if (!hasValidEmail(state.recipient())) {
            deliveries.markSkipped(delivery.id(), state.recipient(), "NO_RECIPIENT");
            return;
        }

        LocalDate slotDate = delivery.scheduledSlot().atZone(zone).toLocalDate();
        String dayReference = slotDate.equals(assessmentDate) ? "hoy" : "mañana";
        Map<String, Object> data = Map.of(
            "applicationId", state.applicationId(),
            "guardianName", escape(state.guardianName()),
            "studentName", escape(state.studentName()),
            "gradeApplied", escape(state.gradeApplied()),
            "academicYear", state.academicYear(),
            "dayReference", dayReference,
            "assessmentDate", assessmentDate.format(DATE_FORMAT));
        String key = "assessment-start-reminder/" + delivery.scheduledSlot() + "/" + state.applicationId();
        try {
            Map<String, Object> result = emailComposer.send(EmailRequestDTO.builder()
                .template(TemplateUtils.generateTemplate(EmailTemplate.ASSESSMENT_START_REMINDER.name(), data))
                .to(state.recipient().trim())
                .subject("Recordatorio: " + dayReference + " comienzan las pruebas de admisión")
                .recipientType("APPLICATION")
                .recipientId(state.applicationId())
                .data(data)
                .sensitive(false)
                .templateName(EmailTemplate.ASSESSMENT_START_REMINDER.name())
                .idempotencyKey(key)
                .build());
            deliveries.markSent(delivery.id(), state.recipient().trim(), providerMessageId(result));
            log.info("Recordatorio de inicio de pruebas enviado deliveryId={} applicationId={}",
                delivery.id(), delivery.applicationId());
        } catch (Exception exception) {
            handleFailure(delivery, state.recipient(), exception);
        }
    }

    private void handleFailure(Delivery delivery, String recipient, Exception exception) {
        String error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        int retryIndex = Math.min(delivery.attempts() - 1, RETRY_MINUTES.length - 1);
        Instant nextAttempt = delivery.attempts() >= maxAttempts ? null
            : clock.instant().plus(Duration.ofMinutes(RETRY_MINUTES[retryIndex]));
        deliveries.markFailed(delivery.id(), recipient,
            error.substring(0, Math.min(error.length(), 2000)), nextAttempt);
        log.error("Falló recordatorio de inicio de pruebas deliveryId={} applicationId={} attempt={}",
            delivery.id(), delivery.applicationId(), delivery.attempts(), exception);
    }

    static boolean eligible(ApplicationState state) {
        return state.active() && (!state.paymentRequired() || state.paymentConfirmed())
            && state.formSubmitted();
    }

    private static boolean hasValidEmail(String email) {
        if (email == null) return false;
        String value = email.trim();
        int at = value.indexOf('@');
        return at > 0 && at < value.length() - 1 && !value.contains(" ");
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
}
