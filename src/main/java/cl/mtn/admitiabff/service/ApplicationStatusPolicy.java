package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Política del proceso general; el módulo y los registros de Prekínder quedan fuera. */
public final class ApplicationStatusPolicy {
    private ApplicationStatusPolicy() {}

    public static boolean applies(ApplicationEntity application) {
        String grade = application.getStudent() == null ? "" : application.getStudent().getGradeApplied();
        String normalized = Normalizer.normalize(grade == null ? "" : grade, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").replaceAll("[^a-zA-Z0-9]", "").toUpperCase(java.util.Locale.ROOT);
        return !normalized.equals("PREKINDER") && !normalized.equals("PK");
    }

    public static List<ApplicationStatus> allowed(ApplicationEntity application) {
        ApplicationStatus current = application.getStatus();
        if (applies(application)) {
            if (current == ApplicationStatus.APPROVED || current == ApplicationStatus.REJECTED) return List.of();
            if (current == ApplicationStatus.WAITLIST) return List.of(ApplicationStatus.APPROVED, ApplicationStatus.REJECTED);
        }
        return Arrays.stream(ApplicationStatus.values()).filter(status -> status != current).toList();
    }

    public static String reason(ApplicationEntity application) {
        if (!applies(application)) return "";
        return switch (application.getStatus()) {
            case APPROVED -> "La postulación está cerrada con resultado Aceptado y no admite cambios de estado.";
            case REJECTED -> "La postulación está cerrada con resultado No seleccionado y no admite cambios de estado.";
            case WAITLIST -> "Lista de espera solo puede cambiar a Aceptado o No seleccionado por una persona autorizada.";
            default -> "";
        };
    }

    public static void validate(ApplicationEntity application, ApplicationStatus next) {
        if (next == null) throw new IllegalArgumentException("Se requiere un estado válido");
        if (application.getStatus() != next && !allowed(application).contains(next)) {
            throw new StatusTransitionException(reason(application));
        }
    }

    public static Map<String, Object> metadata(ApplicationEntity application) {
        return Map.of("allowedStatusTransitions", allowed(application).stream().map(Enum::name).toList(),
            "statusChangeBlockedReason", reason(application));
    }

    public static class StatusTransitionException extends RuntimeException {
        public StatusTransitionException(String message) { super(message); }
    }
}
