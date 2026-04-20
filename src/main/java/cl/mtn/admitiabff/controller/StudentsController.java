package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.StudentService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/students")
public class StudentsController {
    private final StudentService studentService;

    public StudentsController(StudentService studentService) { this.studentService = studentService; }

    @PostMapping("/validate-rut") public Map<String, Object> validateRut(@RequestBody Map<String, Object> payload) { return studentService.validateRut(payload); }
    @GetMapping("/statistics/by-grade") public Map<String, Object> statisticsByGrade() { return studentService.statisticsByGrade(); }
    @GetMapping("/search/{term}") public Map<String, Object> search(@PathVariable String term) { return studentService.search(term); }
    @GetMapping("/grade/{grade}") public Map<String, Object> byGrade(@PathVariable String grade) { return studentService.byGrade(grade); }
    @GetMapping("/rut/{rut}") public Map<String, Object> byRut(@PathVariable String rut) { return studentService.byRut(rut); }
    @GetMapping("/by-guardian/{guardianId}") public Map<String, Object> byGuardian(@PathVariable Long guardianId) { return studentService.byGuardian(guardianId); }
    @GetMapping public Map<String, Object> getAll(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size, @RequestParam(required = false) String gradeApplied, @RequestParam(required = false) String search) { return studentService.getAll(page, size, gradeApplied, search); }
    @GetMapping("/{id}") public Map<String, Object> get(@PathVariable Long id) { return studentService.get(id); }
    @PostMapping public Map<String, Object> create(@RequestBody Map<String, Object> payload) { return studentService.create(payload); }
    @PutMapping("/{id}") public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return studentService.update(id, payload); }
    @DeleteMapping("/{id}") public Map<String, Object> delete(@PathVariable Long id) { return studentService.delete(id); }
}
