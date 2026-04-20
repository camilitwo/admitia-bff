package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.UserService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UsersController {
    private final UserService userService;

    public UsersController(UserService userService) { this.userService = userService; }

    @GetMapping("/roles") public Map<String, Object> roles() { return userService.roles(); }
    @GetMapping("/public/school-staff") public Map<String, Object> publicSchoolStaff(@RequestParam(required = false) Boolean activeOnly) { return userService.publicSchoolStaff(activeOnly); }
    @GetMapping("/me") public Map<String, Object> me() { return userService.me(); }
    @GetMapping("/stats") public Map<String, Object> stats() { return userService.stats(); }
    @GetMapping("/statistics") public Map<String, Object> statistics() { return userService.statistics(); }
    @GetMapping("/by-role/{role}") public Map<String, Object> byRole(@PathVariable String role, @RequestParam(required = false) Boolean activeOnly) { return userService.byRole(role, activeOnly); }
    @GetMapping("/evaluators") public Map<String, Object> evaluators(@RequestParam(required = false) String subject, @RequestParam(required = false) Boolean activeOnly) { return userService.evaluators(subject, activeOnly); }
    @GetMapping("/guardians") public Map<String, Object> guardians(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "15") int size, @RequestParam(required = false) String search, @RequestParam(required = false) Boolean active) { return userService.guardians(page, size, search, active); }
    @GetMapping("/staff") public Map<String, Object> staff(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "15") int size, @RequestParam(required = false) String search, @RequestParam(required = false) String role, @RequestParam(required = false) Boolean active) { return userService.staff(page, size, search, role, active); }
    @GetMapping("/search") public Map<String, Object> search(@RequestParam String query, @RequestParam(required = false) String role, @RequestParam(required = false) Boolean activeOnly) { return userService.search(query, role, activeOnly); }
    @GetMapping("/{id}/associated-data") public Map<String, Object> associatedData(@PathVariable Long id) { return userService.associatedData(id); }
    @GetMapping("/{id}") public Map<String, Object> get(@PathVariable Long id) { return userService.get(id); }
    @GetMapping public Map<String, Object> getAll() { return userService.getAll(); }
    @GetMapping("/cache/stats") public Map<String, Object> cacheStats() { return userService.cacheStats(); }
    @PostMapping public Map<String, Object> create(@RequestBody Map<String, Object> payload) { return userService.create(payload); }
    @PutMapping("/{id}") public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return userService.update(id, payload); }
    @DeleteMapping("/{id}") public Map<String, Object> delete(@PathVariable Long id) { return userService.delete(id); }
    @PatchMapping("/{id}/status") public Map<String, Object> status(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return userService.status(id, Boolean.TRUE.equals(payload.get("active"))); }
    @PutMapping("/{id}/activate") public Map<String, Object> activate(@PathVariable Long id) { return userService.status(id, true); }
    @PutMapping("/{id}/deactivate") public Map<String, Object> deactivate(@PathVariable Long id) { return userService.status(id, false); }
    @PutMapping("/{id}/reset-password") public Map<String, Object> resetPasswordPut(@PathVariable Long id) { return userService.resetPassword(id); }
    @PostMapping("/{id}/reset-password") public Map<String, Object> resetPasswordPost(@PathVariable Long id) { return userService.resetPassword(id); }
    @PostMapping("/{id}/verify-email") public Map<String, Object> verifyEmail(@PathVariable Long id) { return userService.verifyEmail(id); }
    @PatchMapping("/{id}/preferences") public Map<String, Object> preferences(@PathVariable Long id, @RequestBody Map<String, Object> payload) { return userService.preferences(id, payload); }
}
