package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.util.EmailDisplayFormatter;
import cl.mtn.admitiabff.util.TemplateUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AdmissionResultEmailFactory {
    private final EmailComposerService emailComposerService;

    public AdmissionResultEmailFactory(EmailComposerService emailComposerService) {
        this.emailComposerService = emailComposerService;
    }

    public PreparedEmail prepare(ApplicationEntity application) {
        if (application.getGuardian() == null || blank(application.getGuardian().getEmail())) {
            throw new IllegalArgumentException("La postulación no tiene un correo de apoderado formal");
        }
        if (!isFinal(application.getStatus())) {
            throw new IllegalArgumentException("La postulación no tiene una decisión final");
        }

        String guardianName = personName(application.getGuardian().getFullName());
        if (guardianName == null) guardianName = "Apoderado/a";

        String studentName = application.getStudent() == null ? null : personName(
                safe(application.getStudent().getFirstName()) + " "
                        + safe(application.getStudent().getPaternalLastName()) + " "
                        + safe(application.getStudent().getMaternalLastName()));
        if (studentName == null) studentName = "el o la postulante";

        List<String> parents = new ArrayList<>();
        addUnique(parents, application.getFather() == null ? null : application.getFather().getFullName());
        addUnique(parents, application.getMother() == null ? null : application.getMother().getFullName());

        String grade = application.getStudent() == null
                ? ""
                : EmailDisplayFormatter.grade(application.getStudent().getGradeApplied());
        ApplicationStatus status = application.getStatus();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", application.getId());
        data.put("guardianName", escapeHtml(guardianName));
        data.put("familyNames", escapeHtml(guardianName));
        data.put("parentsSummary", escapeHtml(parents.isEmpty() ? "No informados" : String.join(" y ", parents)));
        data.put("studentName", escapeHtml(studentName));
        data.put("gradeApplied", escapeHtml(blank(grade) ? "Curso no informado" : grade));
        data.put("result", label(status));
        data.put("resultBackground", resultBackground(status));
        data.put("resultColor", resultColor(status));
        data.put("resultBorder", resultBorder(status));
        data.put("message", formatMessage(blank(application.getNotes())
                ? defaultMessage(status)
                : application.getNotes().trim()));

        EmailRequestDTO request = EmailRequestDTO.builder()
                .template(TemplateUtils.generateTemplate(EmailTemplate.ADMISSION_RESULT.name(), data))
                .to(application.getGuardian().getEmail().trim().toLowerCase(Locale.ROOT))
                .subject(subject(status))
                .recipientType("APPLICATION")
                .recipientId(application.getId())
                .data(data)
                .templateName(EmailTemplate.ADMISSION_RESULT.name())
                .build();
        EmailComposerService.RenderedEmail rendered = emailComposerService.render(request);
        return new PreparedEmail(request.to, guardianName, rendered.subject(), rendered.html());
    }

    public static boolean isFinal(ApplicationStatus status) {
        return status == ApplicationStatus.APPROVED
                || status == ApplicationStatus.WAITLIST
                || status == ApplicationStatus.REJECTED;
    }

    private static void addUnique(List<String> names, String raw) {
        String name = personName(raw);
        if (name != null && names.stream().noneMatch(existing -> existing.equalsIgnoreCase(name))) {
            names.add(name);
        }
    }

    private static String personName(String raw) {
        if (blank(raw)) return null;
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        String normalized = cleaned.toUpperCase(Locale.ROOT).replace(".", "").replace("/", "").trim();
        if (cleaned.length() < 2 || Set.of("A", "NA", "N A", "NO APLICA", "SIN INFORMACION", "SIN REGISTRO").contains(normalized)) {
            return null;
        }
        Set<String> connectors = Set.of("de", "del", "la", "las", "los", "y");
        String[] words = cleaned.toLowerCase(Locale.forLanguageTag("es-CL")).split(" ");
        for (int i = 0; i < words.length; i++) {
            if (i > 0 && connectors.contains(words[i])) continue;
            words[i] = Character.toUpperCase(words[i].charAt(0)) + words[i].substring(1);
        }
        return String.join(" ", words);
    }

    private static String label(ApplicationStatus status) {
        return switch (status) {
            case APPROVED -> "Aprobada";
            case WAITLIST -> "Lista de espera";
            case REJECTED -> "Rechazada";
            default -> throw new IllegalArgumentException("Estado sin decisión final");
        };
    }

    private static String subject(ApplicationStatus status) {
        return switch (status) {
            case APPROVED -> "Resultado de admisión: postulación aprobada";
            case WAITLIST -> "Resultado de admisión: lista de espera";
            case REJECTED -> "Resultado de admisión: postulación no seleccionada";
            default -> EmailTemplate.ADMISSION_RESULT.getDefaultSubject();
        };
    }

    private static String defaultMessage(ApplicationStatus status) {
        return switch (status) {
            case APPROVED -> "Nos alegra informarles que la postulación fue aprobada. Próximamente recibirán información sobre los siguientes pasos.";
            case WAITLIST -> "La postulación fue incorporada a la lista de espera. Les contactaremos si se libera una vacante.";
            case REJECTED -> "Agradecemos sinceramente el interés y la confianza depositada en nuestro colegio durante este proceso.";
            default -> "El proceso de admisión ha finalizado.";
        };
    }

    private static String resultBackground(ApplicationStatus status) {
        return switch (status) {
            case APPROVED -> "#ecfdf5";
            case WAITLIST -> "#fffbeb";
            case REJECTED -> "#fef2f2";
            default -> "#f8fafc";
        };
    }

    private static String resultColor(ApplicationStatus status) {
        return switch (status) {
            case APPROVED -> "#047857";
            case WAITLIST -> "#a16207";
            case REJECTED -> "#b91c1c";
            default -> "#273b7a";
        };
    }

    private static String resultBorder(ApplicationStatus status) {
        return switch (status) {
            case APPROVED -> "#a7f3d0";
            case WAITLIST -> "#fde68a";
            case REJECTED -> "#fecaca";
            default -> "#e5e7eb";
        };
    }

    private static String formatMessage(String value) {
        return escapeHtml(value).replace("\r\n", "<br/>").replace("\n", "<br/>");
    }

    private static String escapeHtml(String value) {
        return safe(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String safe(String value) { return value == null ? "" : value; }

    public record PreparedEmail(String recipient, String recipientName, String subject, String html) {}
}
