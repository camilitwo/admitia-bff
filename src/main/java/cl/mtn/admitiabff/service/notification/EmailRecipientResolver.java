package cl.mtn.admitiabff.service.notification;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Resuelve el email destinatario de una notificación a partir del recurso
 * (postulación, entrevista, etc.) consultando la base de datos.
 *
 * <p>Reglas (en orden de prioridad para una postulación):
 * <ol>
 *     <li>{@code applicantUser.email} (usuario que postuló)</li>
 *     <li>{@code guardian.email} (apoderado)</li>
 *     <li>{@code father.email}</li>
 *     <li>{@code mother.email}</li>
 *     <li>{@code student.email}</li>
 * </ol>
 *
 * <p>Nunca devuelve direcciones hardcodeadas: si no hay email válido en la BD,
 * devuelve {@link Optional#empty()} y el caller decide cómo manejar el error.
 */
@Service
public class EmailRecipientResolver {

    private final ApplicationRepository applicationRepository;
    private final InterviewRepository interviewRepository;

    public EmailRecipientResolver(ApplicationRepository applicationRepository,
                                  InterviewRepository interviewRepository) {
        this.applicationRepository = applicationRepository;
        this.interviewRepository = interviewRepository;
    }

    /** Resuelve el destinatario para una postulación. */
    public Optional<String> resolveForApplication(Long applicationId) {
        if (applicationId == null) return Optional.empty();
        return applicationRepository.findActiveById(applicationId)
                .flatMap(this::pickEmail);
    }

    /** Resuelve el destinatario para una entrevista (vía su postulación asociada). */
    public Optional<String> resolveForInterview(Long interviewId) {
        if (interviewId == null) return Optional.empty();
        return interviewRepository.findById(interviewId)
                .map(interview -> interview.getApplication())
                .flatMap(this::pickEmail);
    }

    private Optional<String> pickEmail(ApplicationEntity app) {
        if (app == null) return Optional.empty();
        return firstNonBlank(
                app.getApplicantUser() == null ? null : app.getApplicantUser().getEmail(),
                app.getGuardian() == null ? null : app.getGuardian().getEmail(),
                app.getFather() == null ? null : app.getFather().getEmail(),
                app.getMother() == null ? null : app.getMother().getEmail(),
                app.getStudent() == null ? null : app.getStudent().getEmail()
        );
    }

    private static Optional<String> firstNonBlank(String... values) {
        if (values == null) return Optional.empty();
        for (String v : values) {
            if (v != null && !v.isBlank()) return Optional.of(v.trim());
        }
        return Optional.empty();
    }
}

