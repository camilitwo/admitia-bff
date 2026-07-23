package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.InterviewerPairService;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/interviewer-pairs")
public class InterviewerPairsController {
    private final InterviewerPairService pairService;

    public InterviewerPairsController(InterviewerPairService pairService) {
        this.pairService = pairService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> all() { return pairService.all(); }

    @GetMapping("/options")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> options() { return pairService.options(); }

    @GetMapping("/eligible")
    public Map<String, Object> eligible(@RequestParam Long applicationId,
                                        @RequestParam(required = false) String date,
                                        @RequestParam(required = false) String time,
                                        @RequestParam(required = false) Integer duration) {
        return pairService.eligible(applicationId, date, time, duration);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> create(@RequestBody Map<String, Object> payload) { return pairService.create(payload); }

    @PostMapping("/normalization")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> normalize(@RequestParam(defaultValue = "false") boolean execute,
                                         @RequestParam(required = false) String confirmation) {
        return pairService.normalizeHistorical(execute, confirmation);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> revise(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        return pairService.revise(id, payload);
    }

    @PatchMapping("/{id}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> archive(@PathVariable Long id) { return pairService.archive(id); }
}
