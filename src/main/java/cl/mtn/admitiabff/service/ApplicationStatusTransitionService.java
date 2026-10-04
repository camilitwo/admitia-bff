package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.common.Role;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ApplicationStatusTransitionService {
    private final AuthService authService;
    private final JdbcTemplate jdbc;

    public ApplicationStatusTransitionService(AuthService authService, JdbcTemplate jdbc) {
        this.authService = authService;
        this.jdbc = jdbc;
    }

    public void validate(ApplicationEntity application, ApplicationStatus next) {
        ApplicationStatusPolicy.validate(application, next);
        if (application.getStatus() == next || !ApplicationStatusPolicy.applies(application)) return;
        if (application.getStatus() == ApplicationStatus.WAITLIST
                || next == ApplicationStatus.APPROVED || next == ApplicationStatus.REJECTED || next == ApplicationStatus.WAITLIST) {
            var actor = authService.requireAuth();
            if (!authService.hasAnyRoleContext(actor, Role.ADMIN, Role.COORDINATOR)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo administradores o coordinadores pueden registrar la decisión final");
            }
        }
    }

    /** El llamador debe cargar la postulación con findActiveByIdForUpdate en esta transacción. */
    public boolean transition(ApplicationEntity application, ApplicationStatus next) {
        validate(application, next);
        if (application.getStatus() == next) return false;
        if (!ApplicationStatusPolicy.applies(application)) {
            application.setStatus(next);
            return true;
        }
        var actor = authService.requireAuth();
        jdbc.update("""
            INSERT INTO application_status_history(application_id, from_status, to_status, actor_id)
            VALUES (?, ?, ?, ?)
            """, application.getId(), application.getStatus().name(), next.name(), actor.id());
        application.setStatus(next);
        return true;
    }
}
