package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.EvaluationService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationsController {
    private final EvaluationService evaluationService;

    public EvaluationsController(EvaluationService evaluationService) { this.evaluationService = evaluationService; }

    @GetMapping public Map<String, Object> all() { return evaluationService.all(); }
    @GetMapping("/statistics") public Map<String, Object> statistics() { return evaluationService.statistics(); }
    @GetMapping("/assignments") public Map<String, Object> assignments() { return evaluationService.assignments(); }
    @GetMapping("/export") public ResponseEntity<?> export(@RequestParam(required = false) String status, @RequestParam(required = false) String type, @RequestParam(defaultValue = "json") String format) { return evaluationService.export(status, type, format); }
    @GetMapping("/application/{applicationId}") public Map<String, Object> byApplication(@PathVariable Long applicationId) { return evaluationService.byApplication(applicationId); }
    @GetMapping("/evaluator/{evaluatorId}") public Map<String, Object> byEvaluator(@PathVariable Long evaluatorId) { return evaluationService.byEvaluator(evaluatorId); }
    @GetMapping("/evaluator/{id}/pending") public Map<String, Object> evaluatorPending(@PathVariable Long id) { return evaluationService.evaluatorPending(id); }
    @GetMapping("/evaluator/{id}/completed") public Map<String, Object> evaluatorCompleted(@PathVariable Long id) { return evaluationService.evaluatorCompleted(id); }
    @GetMapping("/type/{type}") public Map<String, Object> byType(@PathVariable String type) { return evaluationService.byType(type); }
    @GetMapping("/subject/{subject}") public Map<String, Object> bySubject(@PathVariable String subject) { return evaluationService.bySubject(subject); }
    @GetMapping("/my-evaluations") public Map<String, Object> myEvaluations() { return evaluationService.myEvaluations(); }
    @GetMapping("/family-interview-template/{grade}") public Map<String, Object> familyInterviewTemplate(@PathVariable String grade) { return evaluationService.familyInterviewTemplate(grade); }
    @GetMapping("/{id}") public Map<String, Object> get(@PathVariable Long id) { return evaluationService.get(id); }
    @GetMapping("/{evaluationId}/family-interview-data") public Map<String, Object> familyInterviewData(@PathVariable Long evaluationId) { return evaluationService.familyInterviewData(evaluationId); }
    @PostMapping public Map<String, Object> create(@RequestBody Map<String, Object> payload) { return evaluationService.create(payload); }
    @PutMapping("/{id}") public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return evaluationService.update(id, payload); }
    @DeleteMapping("/{id}") public Map<String, Object> delete(@PathVariable Long id) { return evaluationService.delete(id); }
    @PostMapping("/{id}/complete") public Map<String, Object> complete(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return evaluationService.complete(id, payload); }
    @PostMapping("/{id}/assign") public Map<String, Object> assign(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return evaluationService.assign(id, payload); }
    @PostMapping("/{id}/reschedule") public Map<String, Object> reschedule(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return evaluationService.reschedule(id, payload); }
    @PostMapping("/{id}/cancel") public Map<String, Object> cancel(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return evaluationService.cancel(id, payload); }
    @PutMapping("/{evaluationId}/family-interview-data") public Map<String, Object> saveFamilyInterviewData(@PathVariable Long evaluationId, @RequestBody Map<String, Object> payload) { return evaluationService.saveFamilyInterviewData(evaluationId, payload); }
    @PostMapping("/bulk/assign") public Map<String, Object> bulkAssign(@RequestBody Map<String, Object> payload) { return evaluationService.bulkAssign(payload); }
    @PostMapping("/migrate/interviews") public Map<String, Object> migrateInterviews() { return evaluationService.migrateInterviews(); }
}
