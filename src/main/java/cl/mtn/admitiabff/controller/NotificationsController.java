package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import cl.mtn.admitiabff.service.EmailVerificationService;
import cl.mtn.admitiabff.service.NotificationService;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints HTTP relacionados con notificaciones / emails.
 *
 * <p>Para envío de emails, el <b>único punto de entrada</b> es
 * {@link EmailComposerService}. Todos los endpoints de envío delegan en él.
 * {@link NotificationService} se usa sólo para listado/consulta de
 * notificaciones persistidas y SMS.
 */
@RestController
public class NotificationsController {
    private final NotificationService notificationService;
    private final EmailVerificationService emailVerificationService;
    private final EmailComposerService emailComposerService;

    public NotificationsController(NotificationService notificationService,
                                   EmailVerificationService emailVerificationService,
                                   EmailComposerService emailComposerService) {
        this.notificationService = notificationService;
        this.emailVerificationService = emailVerificationService;
        this.emailComposerService = emailComposerService;
    }

    // ───── Consulta / persistencia ─────
    @GetMapping("/api/notifications")
    public Map<String, Object> list(@RequestParam(required = false) String recipientType,
                                    @RequestParam(required = false) Long recipientId,
                                    @RequestParam(required = false) String channel,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "15") int limit) {
        return notificationService.list(recipientType, recipientId, channel, type, status, page, limit);
    }

    @GetMapping("/api/notifications/{id}")
    public Map<String, Object> get(@PathVariable Long id) { return notificationService.get(id); }

    @DeleteMapping("/api/notifications/{id}")
    public Map<String, Object> delete(@PathVariable Long id) { return notificationService.delete(id); }

    // ───── Envío de email (TODO va por EmailComposerService) ─────

    /**
     * Envío genérico. El body DEBE incluir {@code template} (uno de
     * {@link EmailTemplate}). Acepta también {@code type}/{@code templateName}
     * por retro-compatibilidad.
     */
    @PostMapping("/api/notifications/email")
    public Map<String, Object> email(@RequestBody Map<String, Object> payload) {
        return emailComposerService.sendFromPayload(payload);
    }

    @PostMapping("/api/notifications/email/bulk")
    public Map<String, Object> bulkEmail(@RequestBody Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recipients = (List<Map<String, Object>>) payload.getOrDefault("recipients", List.of());
        List<Map<String, Object>> data = recipients.stream().map(emailComposerService::sendFromPayload).toList();
        return Map.of("success", true, "data", data, "count", data.size());
    }

    @PostMapping("/api/email/send-test")
    public Map<String, Object> sendTest(@RequestBody Map<String, Object> payload) {
        Map<String, Object> request = new LinkedHashMap<>(payload);
        request.put("template", EmailTemplate.TEST.name());
        return emailComposerService.sendFromPayload(request);
    }

    // ───── Emails institucionales (path = template) ─────
    @PostMapping("/api/institutional-emails/document-review/{applicationId}")
    public Map<String, Object> documentReview(@PathVariable Long applicationId, @RequestBody Map<String, Object> payload) {
        return institutional(EmailTemplate.DOCUMENT_REVIEW, applicationId, payload);
    }

    @PostMapping("/api/institutional-emails/application-received/{applicationId}")
    public Map<String, Object> applicationReceived(@PathVariable Long applicationId, @RequestBody(required = false) Map<String, Object> payload) {
        return institutional(EmailTemplate.APPLICATION_RECEIVED, applicationId, payload);
    }

    @PostMapping("/api/institutional-emails/interview-invitation/{interviewId}")
    public Map<String, Object> interviewInvitation(@PathVariable Long interviewId, @RequestBody(required = false) Map<String, Object> payload) {
        return institutional(EmailTemplate.INTERVIEW_INVITATION, interviewId, payload);
    }

    @PostMapping("/api/institutional-emails/status-update/{applicationId}")
    public Map<String, Object> statusUpdate(@PathVariable Long applicationId, @RequestBody Map<String, Object> payload) {
        return institutional(EmailTemplate.STATUS_UPDATE, applicationId, payload);
    }

    @PostMapping("/api/institutional-emails/document-reminder/{applicationId}")
    public Map<String, Object> documentReminder(@PathVariable Long applicationId, @RequestBody Map<String, Object> payload) {
        return institutional(EmailTemplate.DOCUMENT_REMINDER, applicationId, payload);
    }

    @PostMapping("/api/institutional-emails/admission-result/{applicationId}")
    public Map<String, Object> admissionResult(@PathVariable Long applicationId, @RequestBody Map<String, Object> payload) {
        return institutional(EmailTemplate.ADMISSION_RESULT, applicationId, payload);
    }

    @PostMapping("/api/institutional-emails/evaluation-assignment/{evaluationId}")
    public Map<String, Object> evaluationAssignment(@PathVariable Long evaluationId, @RequestBody Map<String, Object> payload) {
        return institutional(EmailTemplate.EVALUATION_ASSIGNMENT, evaluationId, payload);
    }

    @PostMapping("/api/institutional-emails/interview-summary/{applicationId}")
    public Map<String, Object> interviewSummary(@PathVariable Long applicationId, @RequestBody Map<String, Object> payload) {
        return institutional(EmailTemplate.INTERVIEW_SUMMARY, applicationId, payload);
    }

    @GetMapping("/api/institutional-emails/debug")
    public Map<String, Object> debugInstitutional() {
        return Map.of("success", true, "config", notificationService.configStatus());
    }

    // ───── SMS ─────
    @PostMapping("/api/notifications/sms")
    public Map<String, Object> sms(@RequestBody Map<String, Object> payload) { return notificationService.sms(payload); }

    @PostMapping("/api/notifications/sms/bulk")
    public Map<String, Object> bulkSms(@RequestBody Map<String, Object> payload) { return notificationService.bulkSms(payload); }

    // ───── Verificación / config ─────
    @GetMapping("/api/email/config-status")
    public Map<String, Object> configStatus() { return notificationService.configStatus(); }

    @GetMapping("/api/email/check-exists")
    public Map<String, Object> checkExists(@RequestParam String email) { return notificationService.checkEmailExists(email); }

    @PostMapping("/api/email/send-verification")
    public Map<String, Object> sendVerification(@RequestBody Map<String, Object> payload) { return emailVerificationService.sendVerification(payload); }

    @PostMapping("/api/email/verify-code")
    public Map<String, Object> verifyCode(@RequestBody Map<String, Object> payload) { return emailVerificationService.verifyCode(payload); }

    // ───── Helper privado ─────
    private Map<String, Object> institutional(EmailTemplate template, Long resourceId, Map<String, Object> rawPayload) {
        Map<String, Object> payload = rawPayload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rawPayload);
        payload.put("template", template.name());
        if (payload.get("to") == null && payload.get("recipientEmail") != null) {
            payload.put("to", payload.get("recipientEmail"));
        }
        payload.putIfAbsent("recipientType", "APPLICATION");
        payload.putIfAbsent("recipientId", resourceId);
        payload.putIfAbsent("applicationId", resourceId);
        if (payload.get("to") == null) {
            throw new IllegalArgumentException("El campo 'to' (o 'recipientEmail') es obligatorio para enviar el email institucional.");
        }
        return emailComposerService.sendFromPayload(payload);
    }
}
