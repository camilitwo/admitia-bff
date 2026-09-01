package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.config.AuthContext;
import cl.mtn.admitiabff.domain.interview.ManualInterviewCreateRequest;
import cl.mtn.admitiabff.service.InterviewService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/interviews")
public class InterviewsController {
    private static final Logger log = LoggerFactory.getLogger(InterviewsController.class);
    private final InterviewService interviewService;
    private final String bffPublicBaseUrl;

    public InterviewsController(
            InterviewService interviewService,
            @Value("${app.bff.public-base-url}") String bffPublicBaseUrl) {
        this.interviewService = interviewService;
        this.bffPublicBaseUrl = bffPublicBaseUrl;
    }

    @GetMapping("/public/interviewers") public Object publicInterviewers() { return interviewService.publicInterviewers(); }
    @GetMapping public Map<String, Object> all() { return interviewService.all(); }
    @GetMapping("/statistics") public Map<String, Object> statistics() { return interviewService.statistics(); }
    @GetMapping("/calendar") public Map<String, Object> calendar(@RequestParam(required = false) String startDate, @RequestParam(required = false) String endDate, @RequestParam(required = false, defaultValue = "false") boolean includeRejected) { return interviewService.calendar(startDate, endDate, includeRejected); }
    @GetMapping("/weekly-overview") public Map<String, Object> weeklyOverview(@RequestParam String startDate, @RequestParam String endDate, @RequestParam(required = false) Integer duration) { return interviewService.weeklyOverview(startDate, endDate, duration); }
    @GetMapping("/application/{applicationId}") public Map<String, Object> byApplication(@PathVariable Long applicationId) { return interviewService.byApplication(applicationId); }
    @GetMapping("/application/{applicationId}/summary-status") public Map<String, Object> summaryStatus(@PathVariable Long applicationId) { return interviewService.summaryStatus(applicationId); }
    @GetMapping("/interviewer/{interviewerId}") public Map<String, Object> byInterviewer(@PathVariable Long interviewerId) { return interviewService.byInterviewer(interviewerId); }
    @GetMapping("/available-slots") public Map<String, Object> availableSlots(@RequestParam Long interviewerId, @RequestParam String date, @RequestParam(required = false) Integer duration) { return interviewService.availableSlots(interviewerId, date, duration); }
    @GetMapping("/slot-availability") public Map<String, Object> slotAvailability(@RequestParam String date, @RequestParam String time, @RequestParam(required = false) Integer duration) { return interviewService.slotAvailability(date, time, duration); }
    @GetMapping("/next-available-slots") public Map<String, Object> nextAvailableSlots(@RequestParam(required = false) String date, @RequestParam(required = false) Integer days, @RequestParam(required = false) Integer duration) { return interviewService.nextAvailableSlots(date, days, duration); }
    @GetMapping("/{id}") public Map<String, Object> get(@PathVariable Long id) { return interviewService.get(id); }
    @PostMapping public Map<String, Object> create(@RequestBody Map<String, Object> payload) { return interviewService.create(payload); }
    @PostMapping("/manual")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> createManual(@Valid @RequestBody ManualInterviewCreateRequest payload) {
        var auth = AuthContext.get();
        if (auth == null) throw new IllegalStateException("No se pudo identificar al administrador autenticado");
        Map<String, Object> result = interviewService.createManual(payload, auth.id());
        Object rawData = result.get("data");
        if (!(rawData instanceof Map<?, ?> rawDataMap)) {
            return result;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        rawDataMap.forEach((key, value) -> data.put(String.valueOf(key), value));
        data.put("emailRequested", payload.sendEmail());

        boolean emailSent = false;
        String emailMessage = "No se enviaron correos.";
        if (payload.sendEmail()) {
            try {
                Object rawId = data.get("id");
                if (!(rawId instanceof Number interviewId)) {
                    throw new IllegalStateException("La entrevista guardada no devolvió un identificador válido");
                }
                Map<String, Object> emailResult = interviewService.sendInterviewInvitation(
                    interviewId.longValue(), bffPublicBaseUrl);
                emailSent = Boolean.TRUE.equals(emailResult.get("success"));
                emailMessage = String.valueOf(emailResult.getOrDefault(
                    "message", emailSent ? "Correos enviados correctamente." : "No se pudieron enviar los correos."));
            } catch (Exception exception) {
                emailMessage = "La entrevista quedó guardada, pero no se pudieron enviar los correos.";
                log.error("Entrevista manual guardada, pero falló el envío de correos para entrevista {}: {}",
                    data.get("id"), exception.getMessage(), exception);
            }
        }

        data.put("emailSent", emailSent);
        data.put("emailMessage", emailMessage);
        return Map.of(
            "success", true,
            "message", payload.sendEmail() && emailSent
                ? "Entrevista excepcional guardada y correos enviados"
                : payload.sendEmail()
                    ? "Entrevista excepcional guardada, con error en el envío de correos"
                    : "Entrevista excepcional guardada sin enviar correos",
            "data", data
        );
    }
    @PutMapping("/{id}") public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return interviewService.update(id, payload); }
    @DeleteMapping("/{id}") public Map<String, Object> delete(@PathVariable Long id) { return interviewService.delete(id); }
    @PatchMapping("/{id}/cancel") public Map<String, Object> cancel(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> payload) { return interviewService.cancel(id, payload == null ? Map.of() : payload); }
    @PatchMapping("/{id}/reschedule") public Map<String, Object> reschedule(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return interviewService.reschedule(id, payload); }
    @PatchMapping("/{id}/release") public Map<String, Object> release(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> payload) { return interviewService.release(id, payload == null ? Map.of() : payload); }
    @PostMapping("/application/{applicationId}/send-summary") public Map<String, Object> sendSummary(@PathVariable Long applicationId) { return interviewService.sendSummary(applicationId); }

    @PostMapping("/{id}/send-invitation")
    public Map<String, Object> sendInvitation(@PathVariable Long id) {
        return interviewService.sendInterviewInvitation(id, bffPublicBaseUrl);
    }
}
