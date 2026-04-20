package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.InterviewerScheduleService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/interviewer-schedules")
public class InterviewerSchedulesController {
    private final InterviewerScheduleService service;

    public InterviewerSchedulesController(InterviewerScheduleService service) { this.service = service; }

    @GetMapping("/interviewer/{interviewerId}") public Object byInterviewer(@PathVariable Long interviewerId) { return service.byInterviewer(interviewerId); }
    @GetMapping("/interviewer/{interviewerId}/year/{year}") public Object byInterviewerAndYear(@PathVariable Long interviewerId, @PathVariable Integer year) { return service.byInterviewerAndYear(interviewerId, year); }
    @GetMapping("/available") public Map<String, Object> available(@RequestParam String date, @RequestParam String time) { return service.available(date, time); }
    @GetMapping("/interviewers-with-schedules/{year}") public Object interviewersWithSchedules(@PathVariable Integer year) { return service.interviewersWithSchedules(year); }
    @PostMapping public Map<String, Object> create(@RequestBody Map<String, Object> payload) { return service.create(payload); }
    @PostMapping("/interviewer/{interviewerId}/recurring/{year}") public Map<String, Object> createRecurring(@PathVariable Long interviewerId, @PathVariable Integer year, @RequestBody List<Map<String, Object>> payload) { return service.createRecurring(interviewerId, year, payload); }
    @PostMapping("/toggle") public Map<String, Object> toggle(@RequestBody Map<String, Object> payload) { return service.toggle(payload); }
    @PostMapping("/toggle-bulk") public Map<String, Object> toggleBulk(@RequestBody Map<String, Object> payload) { return service.toggleBulk(payload); }
    @PutMapping("/{id}") public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return service.update(id, payload); }
    @PutMapping("/{id}/deactivate") public Map<String, Object> deactivate(@PathVariable Long id) { return service.deactivate(id); }
    @DeleteMapping("/{id}") public Map<String, Object> delete(@PathVariable Long id) { return service.delete(id); }
}
