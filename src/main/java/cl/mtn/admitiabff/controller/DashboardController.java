package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.DashboardService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class DashboardController {
    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) { this.dashboardService = dashboardService; }

    @GetMapping("/api/dashboard/stats") public Map<String, Object> stats() { return dashboardService.generalStats(); }
    @GetMapping("/api/dashboard/admin/stats") public Map<String, Object> adminStats() { return dashboardService.adminStats(); }
    @GetMapping("/api/dashboard/admin/detailed-stats") public Map<String, Object> detailedAdminStats(@RequestParam(required = false) Integer academicYear) { return dashboardService.detailedAdminStats(academicYear); }
    @GetMapping("/api/dashboard/applicants/{id}/summary") public Map<String, Object> applicantSummary(@PathVariable Long id) { return dashboardService.applicantSummary(id); }
    @GetMapping("/api/dashboard/applicant-metrics") public Map<String, Object> applicantMetrics(@RequestParam(required = false) Integer academicYear, @RequestParam(required = false) String grade, @RequestParam(required = false) String status, @RequestParam(required = false) String sortBy, @RequestParam(required = false) String sortOrder) { return dashboardService.applicantMetrics(academicYear, grade, status, sortBy, sortOrder); }
    @PostMapping("/api/dashboard/cache/clear") public Map<String, Object> clearCache(@RequestBody(required = false) Map<String, Object> payload) { return dashboardService.clearCache(payload == null ? null : (String) payload.get("pattern")); }
    @GetMapping("/api/dashboard/cache/stats") public Map<String, Object> cacheStats() { return dashboardService.cacheStats(); }
    @GetMapping("/api/analytics/dashboard-metrics") public Map<String, Object> dashboardMetrics() { return dashboardService.analyticsDashboardMetrics(); }
    @GetMapping("/api/analytics/status-distribution") public Map<String, Object> statusDistribution() { return dashboardService.statusDistribution(); }
    @GetMapping("/api/analytics/temporal-trends") public Map<String, Object> temporalTrends() { return dashboardService.temporalTrends(); }
    @GetMapping("/api/analytics/grade-distribution") public Map<String, Object> gradeDistribution() { return dashboardService.gradeDistribution(); }
    @GetMapping("/api/analytics/insights") public Map<String, Object> insights() { return dashboardService.insights(); }
    @GetMapping("/api/analytics/evaluator-analysis") public Map<String, Object> evaluatorAnalysis() { return dashboardService.evaluatorAnalysis(); }
    @GetMapping("/api/analytics/performance-metrics") public Map<String, Object> performanceMetrics() { return dashboardService.performanceMetrics(); }
}
