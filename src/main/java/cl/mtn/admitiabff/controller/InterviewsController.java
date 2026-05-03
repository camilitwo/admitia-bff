package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.InterviewService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/interviews")
public class InterviewsController {
    private final InterviewService interviewService;

    public InterviewsController(InterviewService interviewService) { this.interviewService = interviewService; }

    @GetMapping("/public/interviewers") public Object publicInterviewers() { return interviewService.publicInterviewers(); }
    @GetMapping public Map<String, Object> all() { return interviewService.all(); }
    @GetMapping("/statistics") public Map<String, Object> statistics() { return interviewService.statistics(); }
    @GetMapping("/calendar") public Map<String, Object> calendar(@RequestParam(required = false) String startDate, @RequestParam(required = false) String endDate) { return interviewService.calendar(startDate, endDate); }
    @GetMapping("/application/{applicationId}") public Map<String, Object> byApplication(@PathVariable Long applicationId) { return interviewService.byApplication(applicationId); }
    @GetMapping("/application/{applicationId}/summary-status") public Map<String, Object> summaryStatus(@PathVariable Long applicationId) { return interviewService.summaryStatus(applicationId); }
    @GetMapping("/interviewer/{interviewerId}") public Map<String, Object> byInterviewer(@PathVariable Long interviewerId) { return interviewService.byInterviewer(interviewerId); }
    @GetMapping("/available-slots") public Map<String, Object> availableSlots(@RequestParam Long interviewerId, @RequestParam String date, @RequestParam(required = false) Integer duration) { return interviewService.availableSlots(interviewerId, date, duration); }
    @GetMapping("/{id}") public Map<String, Object> get(@PathVariable Long id) { return interviewService.get(id); }
    @PostMapping public Map<String, Object> create(@RequestBody Map<String, Object> payload) { return interviewService.create(payload); }
    @PutMapping("/{id}") public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return interviewService.update(id, payload); }
    @DeleteMapping("/{id}") public Map<String, Object> delete(@PathVariable Long id) { return interviewService.delete(id); }
    @PatchMapping("/{id}/cancel") public Map<String, Object> cancel(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> payload) { return interviewService.cancel(id, payload == null ? Map.of() : payload); }
    @PatchMapping("/{id}/reschedule") public Map<String, Object> reschedule(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return interviewService.reschedule(id, payload); }
    @PostMapping("/application/{applicationId}/send-summary") public Map<String, Object> sendSummary(@PathVariable Long applicationId) { return interviewService.sendSummary(applicationId); }
}
