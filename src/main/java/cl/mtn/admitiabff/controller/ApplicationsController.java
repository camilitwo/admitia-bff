package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.ApplicationService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/applications")
public class ApplicationsController {
    private final ApplicationService applicationService;

    public ApplicationsController(ApplicationService applicationService) { this.applicationService = applicationService; }

    @GetMapping("/stats") public Map<String, Object> stats() { return applicationService.stats(); }
    @GetMapping("/statistics") public Map<String, Object> statistics() { return applicationService.stats(); }
    @GetMapping("/public/all") public Map<String, Object> publicAll(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int limit) { return applicationService.publicAll(page, limit); }
    @GetMapping("/{id}/contact") public Map<String, Object> contact(@PathVariable Long id) { return applicationService.contact(id); }
    @GetMapping public Map<String, Object> list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size, @RequestParam(required = false) String status, @RequestParam(required = false) String gradeApplying, @RequestParam(required = false) String search) { return applicationService.list(page, size, status, gradeApplying, search); }
    @GetMapping("/recent") public Map<String, Object> recent(@RequestParam(defaultValue = "10") int limit) { return applicationService.recent(limit); }
    @GetMapping("/requiring-documents") public Map<String, Object> requiringDocuments() { return applicationService.requiringDocuments(); }
    @GetMapping("/search") public Map<String, Object> search(@RequestParam(required = false) String query, @RequestParam(required = false) String studentName, @RequestParam(required = false) String status) { return applicationService.search(query != null ? query : studentName, status); }
    @GetMapping("/export") public ResponseEntity<?> export(@RequestParam(required = false) String status, @RequestParam(defaultValue = "json") String format, @RequestParam(required = false) String search) { return applicationService.export(status, format, search); }
    @GetMapping("/status/{status}") public Map<String, Object> byStatus(@PathVariable String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int limit) { return applicationService.byStatus(status, page, limit); }
    @GetMapping("/user/{userId}") public Map<String, Object> byUser(@PathVariable Long userId) { return applicationService.byUser(userId); }
    @GetMapping("/my-applications") public Map<String, Object> myApplications() { return applicationService.myApplications(); }
    @GetMapping("/for-evaluation/{evaluatorId}") public Map<String, Object> forEvaluation(@PathVariable Long evaluatorId) { return applicationService.forEvaluation(evaluatorId); }
    @GetMapping("/special-category/{category}") public Map<String, Object> specialCategory(@PathVariable String category) { return applicationService.specialCategory(category); }
    @GetMapping("/{id}") public Map<String, Object> get(@PathVariable Long id) { return applicationService.get(id); }
    @GetMapping("/{id}/documents") public Map<String, Object> documents(@PathVariable Long id) { return applicationService.documents(id); }
    @GetMapping("/{id}/complementary-form") public Map<String, Object> complementaryForm(@PathVariable Long id) { return applicationService.complementaryForm(id); }
    @PostMapping public Map<String, Object> create(@RequestBody Map<String, Object> payload) { return applicationService.create(payload); }
    @PutMapping("/{id}") public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return applicationService.update(id, payload); }
    @PatchMapping("/{id}/status") public Map<String, Object> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return applicationService.updateStatus(id, payload); }
    @PatchMapping("/{id}/document-notification-sent") public Map<String, Object> markDocumentNotificationSent(@PathVariable Long id) { return applicationService.markDocumentNotificationSent(id); }
    @PutMapping("/{id}/archive") public Map<String, Object> archive(@PathVariable Long id) { return applicationService.archive(id); }
    @DeleteMapping("/{id}") public Map<String, Object> delete(@PathVariable Long id) { return applicationService.delete(id); }
    @PostMapping("/bulk/update-status") public Map<String, Object> bulkUpdateStatus(@RequestBody Map<String, Object> payload) { return applicationService.bulkUpdateStatus(payload); }
    @PostMapping("/{id}/complementary-form") public Map<String, Object> upsertComplementaryForm(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return applicationService.upsertComplementaryForm(id, payload); }
    @PostMapping("/cache/clear") public Map<String, Object> clearCache() { return applicationService.clearCache(); }
    @GetMapping("/debug/system-info") public Map<String, Object> systemInfo() { return applicationService.systemInfo(); }
}
