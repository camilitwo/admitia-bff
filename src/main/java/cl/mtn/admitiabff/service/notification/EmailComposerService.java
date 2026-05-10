package cl.mtn.admitiabff.service.notification;

import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import cl.mtn.admitiabff.service.NotificationService;
import cl.mtn.admitiabff.service.notification.template.EmailTemplateRegistry;
import cl.mtn.admitiabff.util.TemplateUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * Orquestador central para envío de emails.
 *
 * <p>Carga el layout base {@code templateCorreo.html} desde el classpath
 * (carpeta {@code template/}) y reemplaza el placeholder
 * {@code {{html_replace}}} con el body que viene en
 * {@link EmailRequestDTO#template} (resuelto por {@link TemplateUtils}).
 *
 * <p>Existen dos vías de invocación:
 * <ul>
 *   <li>{@link #send(EmailRequestDTO)} — typed, para services que ya
 *       resolvieron el body con {@code TemplateUtils.generateTemplate(...)}.</li>
 *   <li>{@link #sendFromPayload(Map)} — para los controllers institucionales
 *       que pasan el {@code template} (enum) y un {@code data}; aquí mismo
 *       resolvemos el body por flujo y se delega a {@link #send}.</li>
 * </ul>
 */
@Service
public class EmailComposerService {

    private static final Logger log = LoggerFactory.getLogger(EmailComposerService.class);

    /** Path en el classpath del layout base de correo. */
    private static final String BASE_TEMPLATE_PATH = "template/templateCorreo.html";
    private static final String BODY_PLACEHOLDER = "{{html_replace}}";

    private final EmailTemplateRegistry registry;
    private final NotificationService notificationService;

    /** Cache lazy del layout base; se carga una sola vez. */
    private volatile String baseLayoutCache;

    public EmailComposerService(EmailTemplateRegistry registry,
                                @Lazy NotificationService notificationService) {
        this.registry = registry;
        this.notificationService = notificationService;
    }

    // ------------------------------------------------------------------
    // API por payload Map (controllers institucionales).
    // ------------------------------------------------------------------
    public Map<String, Object> sendFromPayload(Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload requerido");

        String rawTemplate = firstNonBlank(
                stringOrNull(payload.get("template")),
                stringOrNull(payload.get("templateName")),
                stringOrNull(payload.get("type"))
        );
        EmailTemplate template = EmailTemplate.from(rawTemplate); // null/blank -> GENERIC

        @SuppressWarnings("unchecked")
        Map<String, Object> data = payload.get("data") instanceof Map
                ? (Map<String, Object>) payload.get("data")
                : payload; // si el front no anida, usamos todo el payload como data

        // Resuelve el body HTML específico del flujo (mismo layout, contenido distinto).
        String bodyHtml = TemplateUtils.generateTemplate(template.name(), data);

        Long recipientId = payload.get("recipientId") instanceof Number n ? n.longValue() : null;

        EmailRequestDTO request = EmailRequestDTO.builder()
                .template(bodyHtml)
                .to(stringOrNull(payload.get("to")))
                .subject(firstNonBlank(stringOrNull(payload.get("subject")), template.getDefaultSubject()))
                .recipientType(stringOrNull(payload.get("recipientType")))
                .recipientId(recipientId)
                .data(data)
                .build();

        log.debug("Email compose payload template={} to={}", template, request.to);
        return send(request);
    }

    // ------------------------------------------------------------------
    // API tipada (services internos).
    // ------------------------------------------------------------------
    public Map<String, Object> send(EmailRequestDTO request) {
        Objects.requireNonNull(request, "request requerido");
        if (request.to == null || request.to.isBlank() || "null".equalsIgnoreCase(request.to)) {
            throw new IllegalArgumentException(
                    "Destinatario (to) requerido y no puede estar vacío. Resolverlo desde el front o desde la base de datos antes de invocar al composer.");
        }

        Map<String, Object> data = request.data == null ? Map.of() : request.data;

        // request.template es el FRAGMENTO HTML (body) que reemplaza {{html_replace}}.
        // Si viene null/blank, usamos un body genérico del flujo GENERIC.
        String body = (request.template == null || request.template.isBlank())
                ? TemplateUtils.generateTemplate(EmailTemplate.GENERIC.name(), data)
                : request.template;

        String html = loadBaseLayout().replace(BODY_PLACEHOLDER, body);

        String subject = firstNonBlank(request.subject, "Notificación MTN");

        log.debug("Email send to={} subject={}", request.to, subject);

        Map<String, Object> mailPayload = new LinkedHashMap<>();
        mailPayload.put("to", request.to);
        mailPayload.put("subject", subject);
        mailPayload.put("message", html); // HTML final renderizado
        mailPayload.put("templateData", data);
        if (request.recipientType != null) mailPayload.put("recipientType", request.recipientType);
        if (request.recipientId != null) mailPayload.put("recipientId", request.recipientId);

        return notificationService.email(mailPayload);
    }

    // ------------------------------------------------------------------
    // Helpers internos.
    // ------------------------------------------------------------------

    /** Carga (con cache) el HTML del layout base desde {@code classpath:template/templateCorreo.html}. */
    private String loadBaseLayout() {
        String cached = baseLayoutCache;
        if (cached != null) return cached;
        synchronized (this) {
            if (baseLayoutCache != null) return baseLayoutCache;
            ClassPathResource resource = new ClassPathResource(BASE_TEMPLATE_PATH);
            if (!resource.exists()) {
                throw new IllegalStateException(
                        "No se encontró el layout base de correo en classpath:" + BASE_TEMPLATE_PATH);
            }
            try {
                baseLayoutCache = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Error leyendo " + BASE_TEMPLATE_PATH, e);
            }
            return baseLayoutCache;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String stringOrNull(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }
}
