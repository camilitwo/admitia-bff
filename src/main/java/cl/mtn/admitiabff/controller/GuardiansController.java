package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.GuardianService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/guardians")
public class GuardiansController {
    private final GuardianService guardianService;

    public GuardiansController(GuardianService guardianService) { this.guardianService = guardianService; }

    @GetMapping public Map<String, Object> getAll(@RequestParam(required = false) String relationship, @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "15") int limit) { return guardianService.getAll(relationship, search, page, limit); }
    @GetMapping("/stats") public Map<String, Object> stats() { return guardianService.stats(); }
    @GetMapping("/{id}") public Map<String, Object> byId(@PathVariable Long id) { return guardianService.byId(id); }
    @GetMapping("/rut/{rut}") public Map<String, Object> byRut(@PathVariable String rut) { return guardianService.byRut(rut); }
    @GetMapping("/user/{userId}") public Object byUser(@PathVariable Long userId) { return guardianService.byUser(userId); }
    @PostMapping public Map<String, Object> create(@RequestBody Map<String, Object> payload) { return guardianService.create(payload); }
    @PutMapping("/{id}") public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return guardianService.update(id, payload); }
    @DeleteMapping("/{id}") public Map<String, Object> delete(@PathVariable Long id) { return guardianService.delete(id); }
}
