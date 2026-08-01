package cl.mtn.admitiabff.service.notification;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** Datos institucionales que no deben depender de que el front los repita. */
    public Map<String, Object> resolveContextForApplication(Long applicationId) {
        if (applicationId == null) return Map.of();
        return applicationRepository.findActiveById(applicationId)
            .map(this::applicationContext)
            .orElseGet(Map::of);
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

    private Map<String, Object> applicationContext(ApplicationEntity app) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("applicationId", app.getId());
        if (app.getStudent() != null) {
            putIfNotBlank(context, "studentName", joinPersonName(
                app.getStudent().getFirstName(),
                app.getStudent().getPaternalLastName(),
                app.getStudent().getMaternalLastName()));
        }

        String parentNames = joinNames(
            app.getFather() == null ? null : app.getFather().getFullName(),
            app.getMother() == null ? null : app.getMother().getFullName());
        if (parentNames.isBlank() && app.getGuardian() != null) {
            parentNames = clean(app.getGuardian().getFullName());
        }
        if (parentNames.isBlank() && app.getApplicantUser() != null) {
            parentNames = joinPersonName(app.getApplicantUser().getFirstName(), app.getApplicantUser().getLastName());
        }
        putIfNotBlank(context, "parentNames", parentNames);
        return context;
    }

    private static void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private static String joinNames(String... values) {
        if (values == null) return "";
        return java.util.Arrays.stream(values)
            .map(EmailRecipientResolver::clean)
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.joining(" y "));
    }

    private static String joinPersonName(String... values) {
        if (values == null) return "";
        return java.util.Arrays.stream(values)
            .map(EmailRecipientResolver::clean)
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static Optional<String> firstNonBlank(String... values) {
        if (values == null) return Optional.empty();
        for (String v : values) {
            if (v != null && !v.isBlank()) return Optional.of(v.trim());
        }
        return Optional.empty();
    }
}
