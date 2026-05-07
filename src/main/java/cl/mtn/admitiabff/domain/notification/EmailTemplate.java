package cl.mtn.admitiabff.domain.notification;

import java.util.Arrays;
import java.util.Optional;

/**
 * Catálogo de plantillas de email soportadas por el sistema.
 * <p>
 * Patrón aplicado: <b>Strategy + Registry (coreografía)</b>.
 * El front envía el campo {@code template} en el body con uno de estos valores
 * y el {@code EmailComposerService} delega al {@code EmailTemplateRenderer}
 * registrado para ese enum, que arma el HTML final.
 * <p>
 * Cada template tiene:
 * <ul>
 *     <li>{@link #defaultSubject}: subject por defecto si el caller no lo provee.</li>
 *     <li>{@link #category}: agrupación funcional (auth, application, interview, evaluation, system).</li>
 * </ul>
 */
public enum EmailTemplate {

    // ---------- Auth ----------
    WELCOME("¡Bienvenido a MTN!", Category.AUTH),
    EMAIL_VERIFICATION("Código de verificación", Category.AUTH),
    PASSWORD_RESET("Recupera tu contraseña", Category.AUTH),
    PASSWORD_CHANGED("Tu contraseña fue actualizada", Category.AUTH),
    USER_INVITATION("Invitación a la plataforma MTN", Category.AUTH),

    // ---------- Application ----------
    APPLICATION_RECEIVED("Hemos recibido tu postulación", Category.APPLICATION),
    DOCUMENT_REVIEW("Revisión de documentos", Category.APPLICATION),
    DOCUMENT_REMINDER("Recordatorio: documentos pendientes", Category.APPLICATION),
    STATUS_UPDATE("Actualización de tu postulación", Category.APPLICATION),
    ADMISSION_RESULT("Resultado de admisión", Category.APPLICATION),

    // ---------- Interview ----------
    INTERVIEW_INVITATION("Invitación a entrevista", Category.INTERVIEW),
    INTERVIEW_RESCHEDULED("Tu entrevista fue reprogramada", Category.INTERVIEW),
    INTERVIEW_CANCELLED("Tu entrevista fue cancelada", Category.INTERVIEW),
    INTERVIEW_SUMMARY("Resumen de entrevistas", Category.INTERVIEW),

    // ---------- Evaluation ----------
    EVALUATION_ASSIGNMENT("Nueva evaluación asignada", Category.EVALUATION),
    EVALUATION_COMPLETED("Evaluación completada", Category.EVALUATION),
    EVALUATION_RESCHEDULED("Evaluación reprogramada", Category.EVALUATION),
    EVALUATION_CANCELLED("Evaluación cancelada", Category.EVALUATION),

    // ---------- System / fallback ----------
    GENERIC("Notificación MTN", Category.SYSTEM),
    PLAIN_TEXT("Notificación MTN", Category.SYSTEM),
    TEST("Correo de prueba MTN", Category.SYSTEM);

    private final String defaultSubject;
    private final Category category;

    EmailTemplate(String defaultSubject, Category category) {
        this.defaultSubject = defaultSubject;
        this.category = category;
    }

    public String getDefaultSubject() {
        return defaultSubject;
    }

    public Category getCategory() {
        return category;
    }

    /** Resolución tolerante: acepta nombres en cualquier case y null/blank → GENERIC. */
    public static EmailTemplate from(String value) {
        if (value == null || value.isBlank()) {
            return GENERIC;
        }
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Template de email no soportado: '" + value + "'. Valores válidos: " + Arrays.toString(values())));
    }

    public static Optional<EmailTemplate> tryFrom(String value) {
        try {
            return Optional.of(from(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public enum Category {
        AUTH, APPLICATION, INTERVIEW, EVALUATION, SYSTEM
    }
}

