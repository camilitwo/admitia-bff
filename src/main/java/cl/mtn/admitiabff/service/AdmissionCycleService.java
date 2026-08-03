package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.application.AdmissionCycleEntity;
import cl.mtn.admitiabff.domain.common.AdmissionCycleStatus;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.repository.AdmissionCycleRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class AdmissionCycleService {
    private static final String EMAIL_REGEX = "^[^[:space:]@]+@[^[:space:]@]+\\.[^[:space:]@]+$";

    private final AdmissionCycleRepository cycleRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;
    private final boolean closeEnabled;
    private final boolean dispatchEnabled;
    private final boolean emailMockMode;
    private final String resendApiKey;
    private final String emailFrom;

    public AdmissionCycleService(
            AdmissionCycleRepository cycleRepository,
            JdbcTemplate jdbcTemplate,
            AuthService authService,
            @Value("${app.admission-cycle.close-enabled:false}") boolean closeEnabled,
            @Value("${app.admission-cycle.dispatch-enabled:false}") boolean dispatchEnabled,
            @Value("${app.email.mock-mode:false}") boolean emailMockMode,
            @Value("${app.email.resend.api-key:}") String resendApiKey,
            @Value("${app.email.from:}") String emailFrom) {
        this.cycleRepository = cycleRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
        this.closeEnabled = closeEnabled;
        this.dispatchEnabled = dispatchEnabled;
        this.emailMockMode = emailMockMode;
        this.resendApiKey = resendApiKey;
        this.emailFrom = emailFrom;
    }

    public Map<String, Object> current() {
        requireAdmin();
        AdmissionCycleEntity cycle = cycleRepository.findFirstByOrderByAcademicYearDesc()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe un ciclo de admisión configurado"));
        return response(cycle, preflight(cycle.getAcademicYear()), false);
    }

    @Transactional
    public Map<String, Object> close(Integer academicYear, String confirmationText) {
        AuthService.AuthContextHolder auth = requireAdmin();
        requireOperationalReadiness();
        AdmissionCycleEntity cycle = cycleRepository.findByAcademicYearForUpdate(academicYear)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ciclo de admisión no encontrado"));

        if (cycle.getStatus() != AdmissionCycleStatus.OPEN) {
            return response(cycle, preflight(academicYear), true);
        }

        String expected = confirmationPhrase(academicYear);
        if (confirmationText == null || !expected.equals(confirmationText.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El texto de confirmación no coincide");
        }

        Preflight check = preflight(academicYear);
        validatePreflight(check);

        int inserted = jdbcTemplate.update("""
                INSERT INTO admission_result_dispatches (
                    cycle_id, application_id, recipient_email, recipient_name, status,
                    attempts, next_attempt_at, idempotency_key, created_at, updated_at
                )
                SELECT c.id, a.id, lower(trim(g.email)), trim(g.full_name), 'PENDING',
                       0, CURRENT_TIMESTAMP,
                       'admission-result/' || c.academic_year || '/' || a.id,
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM applications a
                JOIN guardians g ON g.id = a.guardian_id
                JOIN admission_cycles c ON c.academic_year = a.academic_year
                WHERE c.id = ?
                  AND a.deleted_at IS NULL
                  AND a.is_archived = false
                  AND a.status IN ('APPROVED', 'WAITLIST', 'REJECTED')
                  AND g.email IS NOT NULL
                  AND trim(g.email) ~* ?
                ON CONFLICT (cycle_id, application_id) DO NOTHING
                """, cycle.getId(), EMAIL_REGEX);

        Long queued = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admission_result_dispatches WHERE cycle_id = ?",
                Long.class,
                cycle.getId());
        long queuedCount = queued == null ? 0 : queued;
        if (queuedCount != check.totalApplications()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Verificación de seguridad fallida: el total encolado no coincide con las postulaciones validadas");
        }

        LocalDateTime now = LocalDateTime.now();
        cycle.setStatus(AdmissionCycleStatus.PUBLISHING);
        cycle.setStartedAt(now);
        cycle.setClosedAt(null);
        cycle.setClosedByUserId(auth.id());
        cycle.setTotalApplications(check.totalApplications());
        cycle.setQueuedCount(queuedCount);
        cycle.setSentCount(0);
        cycle.setFailedCount(0);
        cycleRepository.save(cycle);

        Map<String, Object> result = response(cycle, check, inserted == 0);
        result.put("message", "Cierre iniciado; los resultados fueron liberados para envío");
        return result;
    }

    @Transactional
    public Map<String, Object> retryFailed(Integer academicYear) {
        requireAdmin();
        requireOperationalReadiness();
        AdmissionCycleEntity cycle = cycleRepository.findByAcademicYearForUpdate(academicYear)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ciclo de admisión no encontrado"));
        if (cycle.getStatus() != AdmissionCycleStatus.CLOSED_WITH_ERRORS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El ciclo no tiene correos fallidos para reintentar");
        }
        int retried = jdbcTemplate.update("""
                UPDATE admission_result_dispatches
                   SET status = 'PENDING', attempts = 0, next_attempt_at = CURRENT_TIMESTAMP,
                       locked_at = NULL, lease_token = NULL, first_attempt_at = NULL,
                       last_error = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE cycle_id = ? AND status = 'FAILED'
                """, cycle.getId());
        if (retried == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No existen correos fallidos para reintentar");
        }
        cycle.setStatus(AdmissionCycleStatus.PUBLISHING);
        cycle.setClosedAt(null);
        cycle.setFailedCount(0);
        cycleRepository.save(cycle);
        Map<String, Object> result = response(cycle, preflight(academicYear), false);
        result.put("message", "Se reintentará el envío de " + retried + " correo(s) fallido(s)");
        return result;
    }

    private Preflight preflight(Integer academicYear) {
        return jdbcTemplate.queryForObject("""
                SELECT
                    count(*) FILTER (WHERE a.academic_year = ?) AS total,
                    count(*) FILTER (
                        WHERE a.academic_year = ?
                          AND a.status NOT IN ('APPROVED', 'WAITLIST', 'REJECTED')
                    ) AS pending,
                    count(*) FILTER (WHERE a.academic_year = ? AND a.guardian_id IS NULL) AS missing_guardian,
                    count(*) FILTER (
                        WHERE a.academic_year = ? AND a.guardian_id IS NOT NULL
                          AND (g.email IS NULL OR trim(g.email) !~* ?)
                    ) AS invalid_email,
                    count(*) FILTER (WHERE a.academic_year IS NULL) AS missing_year
                FROM applications a
                LEFT JOIN guardians g ON g.id = a.guardian_id
                WHERE a.deleted_at IS NULL AND a.is_archived = false
                """, (rs, rowNum) -> new Preflight(
                    rs.getLong("total"),
                    rs.getLong("pending"),
                    rs.getLong("missing_guardian"),
                    rs.getLong("invalid_email"),
                    rs.getLong("missing_year")),
                academicYear, academicYear, academicYear, academicYear, EMAIL_REGEX);
    }

    private Map<String, Object> response(AdmissionCycleEntity cycle, Preflight check, boolean idempotent) {
        DispatchCounts dispatch = dispatchCounts(cycle.getId());
        boolean deliveryReady = deliveryReady();
        boolean canClose = closeEnabled && dispatchEnabled && deliveryReady
                && cycle.getStatus() == AdmissionCycleStatus.OPEN
                && check.totalApplications() > 0 && !check.hasBlockers();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", cycle.getId());
        data.put("academicYear", cycle.getAcademicYear());
        data.put("status", cycle.getStatus().name());
        data.put("confirmationPhrase", confirmationPhrase(cycle.getAcademicYear()));
        data.put("enabled", closeEnabled);
        data.put("dispatchEnabled", dispatchEnabled);
        data.put("deliveryReady", deliveryReady);
        data.put("emailMockMode", emailMockMode);
        data.put("totalApplications", check.totalApplications());
        data.put("pendingDecisions", check.pendingDecisions());
        data.put("missingGuardians", check.missingGuardians());
        data.put("invalidGuardianEmails", check.invalidGuardianEmails());
        data.put("missingAcademicYear", check.missingAcademicYear());
        data.put("queued", dispatch.total());
        data.put("pending", dispatch.pending());
        data.put("processing", dispatch.processing());
        data.put("sent", dispatch.sent());
        data.put("failed", dispatch.failed());
        data.put("unknown", dispatch.unknown());
        data.put("canClose", canClose);
        data.put("idempotent", idempotent);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", data);
        return result;
    }

    private DispatchCounts dispatchCounts(Long cycleId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'PROCESSING') AS processing,
                       count(*) FILTER (WHERE status = 'SENT') AS sent,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE status = 'UNKNOWN') AS unknown_count
                  FROM admission_result_dispatches WHERE cycle_id = ?
                """, (rs, rowNum) -> new DispatchCounts(
                    rs.getLong("total"), rs.getLong("pending"), rs.getLong("processing"),
                    rs.getLong("sent"), rs.getLong("failed"), rs.getLong("unknown_count")), cycleId);
    }

    private void validatePreflight(Preflight check) {
        if (check.totalApplications() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No existen postulaciones activas para el ciclo");
        }
        if (check.missingAcademicYear() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hay postulaciones activas sin año académico");
        }
        if (check.pendingDecisions() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hay postulaciones sin decisión final");
        }
        if (check.missingGuardians() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hay postulaciones sin apoderado formal");
        }
        if (check.invalidGuardianEmails() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hay apoderados sin un correo válido");
        }
    }

    private void requireOperationalReadiness() {
        if (!closeEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "El cierre maestro está deshabilitado");
        }
        if (!dispatchEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "El trabajador de resultados está deshabilitado");
        }
        if (!deliveryReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "El proveedor de correo no está listo para un envío real");
        }
    }

    private boolean deliveryReady() {
        return !emailMockMode && resendApiKey != null && !resendApiKey.isBlank()
                && emailFrom != null && !emailFrom.isBlank();
    }

    private AuthService.AuthContextHolder requireAdmin() {
        AuthService.AuthContextHolder auth = authService.requireAuth();
        if (!authService.hasAnyRoleContext(auth, Role.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo un administrador puede cerrar el proceso de admisión");
        }
        return auth;
    }

    private static String confirmationPhrase(Integer year) {
        return "terminar postulacion año " + year;
    }

    private record Preflight(long totalApplications, long pendingDecisions, long missingGuardians,
                             long invalidGuardianEmails, long missingAcademicYear) {
        boolean hasBlockers() {
            return pendingDecisions > 0 || missingGuardians > 0 || invalidGuardianEmails > 0 || missingAcademicYear > 0;
        }
    }

    private record DispatchCounts(long total, long pending, long processing, long sent, long failed, long unknown) {}
}
