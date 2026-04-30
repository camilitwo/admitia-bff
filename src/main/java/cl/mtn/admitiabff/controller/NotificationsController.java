package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.EmailVerificationService;
import cl.mtn.admitiabff.service.NotificationService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class NotificationsController {
    private final NotificationService notificationService;
    private final EmailVerificationService emailVerificationService;

    public NotificationsController(NotificationService notificationService, EmailVerificationService emailVerificationService) {
        this.notificationService = notificationService;
        this.emailVerificationService = emailVerificationService;
    }

    @GetMapping("/api/notifications") public Map<String, Object> list(@RequestParam(required = false) String recipientType, @RequestParam(required = false) Long recipientId, @RequestParam(required = false) String channel, @RequestParam(required = false) String type, @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "15") int limit) { return notificationService.list(recipientType, recipientId, channel, type, status, page, limit); }
    @GetMapping("/api/notifications/{id}") public Map<String, Object> get(@PathVariable Long id) { return notificationService.get(id); }
    @PostMapping("/api/notifications/email") public Map<String, Object> email(@RequestBody Map<String, Object> payload) { return notificationService.email(payload); }
    @PostMapping("/api/notifications/sms") public Map<String, Object> sms(@RequestBody Map<String, Object> payload) { return notificationService.sms(payload); }
    @PostMapping("/api/notifications/email/bulk") public Map<String, Object> bulkEmail(@RequestBody Map<String, Object> payload) { return notificationService.bulkEmail(payload); }
    @PostMapping("/api/notifications/sms/bulk") public Map<String, Object> bulkSms(@RequestBody Map<String, Object> payload) { return notificationService.bulkSms(payload); }
    @DeleteMapping("/api/notifications/{id}") public Map<String, Object> delete(@PathVariable Long id) { return notificationService.delete(id); }
    @GetMapping("/api/email/config-status") public Map<String, Object> configStatus() { return notificationService.configStatus(); }
    @GetMapping("/api/email/check-exists") public Map<String, Object> checkExists(@RequestParam String email) { return notificationService.checkEmailExists(email); }
    @PostMapping("/api/email/send-verification") public Map<String, Object> sendVerification(@RequestBody Map<String, Object> payload) { return emailVerificationService.sendVerification(payload); }
    @PostMapping("/api/email/verify-code") public Map<String, Object> verifyCode(@RequestBody Map<String, Object> payload) { return emailVerificationService.verifyCode(payload); }
    @PostMapping("/api/email/send-test") public Map<String, Object> sendTest(@RequestBody Map<String, Object> payload) { return notificationService.sendTest(payload); }
    @GetMapping("/api/institutional-emails/debug") public Map<String, Object> debugInstitutional() { return Map.of("success", true, "config", notificationService.configStatus()); }
    @PostMapping("/api/institutional-emails/document-review/{applicationId}") public Map<String, Object> documentReview(@PathVariable Long applicationId, @RequestBody Map<String, Object> payload) { return notificationService.institutional("DOCUMENT_REVIEW", applicationId, payload); }
    @PostMapping("/api/institutional-emails/application-received/{applicationId}") public Map<String, Object> applicationReceived(@PathVariable Long applicationId) { return notificationService.institutional("APPLICATION_RECEIVED", applicationId, Map.of()); }
    @PostMapping("/api/institutional-emails/interview-invitation/{interviewId}") public Map<String, Object> interviewInvitation(@PathVariable Long interviewId) { return notificationService.institutional("INTERVIEW_INVITATION", interviewId, Map.of()); }
    @PostMapping("/api/institutional-emails/status-update/{applicationId}") public Map<String, Object> statusUpdate(@PathVariable Long applicationId, @RequestBody Map<String, Object> payload) { return notificationService.institutional("STATUS_UPDATE", applicationId, payload); }
    @PostMapping("/api/institutional-emails/document-reminder/{applicationId}") public Map<String, Object> documentReminder(@PathVariable Long applicationId, @RequestBody Map<String, Object> payload) { return notificationService.institutional("DOCUMENT_REMINDER", applicationId, payload); }
    @PostMapping("/api/institutional-emails/admission-result/{applicationId}") public Map<String, Object> admissionResult(@PathVariable Long applicationId, @RequestBody Map<String, Object> payload) { return notificationService.institutional("ADMISSION_RESULT", applicationId, payload); }
    @PostMapping("/api/institutional-emails/evaluation-assignment/{evaluationId}") public Map<String, Object> evaluationAssignment(@PathVariable Long evaluationId, @RequestBody Map<String, Object> payload) { return notificationService.institutional("EVALUATION_ASSIGNMENT", evaluationId, payload); }
    @PostMapping("/api/institutional-emails/interview-summary/{applicationId}") public Map<String, Object> interviewSummary(@PathVariable Long applicationId, @RequestBody Map<String, Object> payload) { return notificationService.institutional("INTERVIEW_SUMMARY", applicationId, payload); }
}
