package cl.mtn.admitiabff.service.notification;

import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import cl.mtn.admitiabff.service.NotificationService;
import cl.mtn.admitiabff.service.notification.template.EmailTemplateRegistry;
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
 * Orquestador central para envío de emails con plantilla.
 *
 * <p>Carga el layout base {@code templateCorreo.html} desde el classpath
 * (carpeta {@code template/}) y reemplaza el placeholder
 * {@code {{html_replace}}} con el body que viene en
 * {@link EmailRequestDTO#template} (resuelto por {@code TemplateUtils}).
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

    public Map<String, Object> sendFromPayload(Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload requerido");
        // Implementación por payload Map se mantiene como TODO; los services
        // tipados deben usar #send(EmailRequestDTO).
        return null;
    }

    /** API tipada para invocar desde otros services sin pasar por payload Map. */
    public Map<String, Object> send(EmailRequestDTO request) {
        Objects.requireNonNull(request, "request requerido");
        if (request.to == null || request.to.isBlank() || "null".equalsIgnoreCase(request.to)) {
            throw new IllegalArgumentException(
                    "Destinatario (to) requerido y no puede estar vacío. Resolverlo desde el front o desde la base de datos antes de invocar al composer.");
        }

        Map<String, Object> data = request.data == null ? Map.of() : request.data;

        // request.template es el FRAGMENTO HTML (body) que reemplaza {{html_replace}}.
        // Si viene null/blank, usamos un mensaje por defecto.
        String body = (request.template == null || request.template.isBlank())
                ? "<tr><td style=\"padding:30px;\"><p>Notificación MTN</p></td></tr>"
                : request.template;

        String html = loadBaseLayout().replace(BODY_PLACEHOLDER, body);

        String subject = firstNonBlank(request.subject, "Notificación MTN");

        log.debug("Email compose to={} subject={}", request.to, subject);

        Map<String, Object> mailPayload = new LinkedHashMap<>();
        mailPayload.put("to", request.to);
        mailPayload.put("subject", subject);
        mailPayload.put("message", html); // HTML final renderizado
        mailPayload.put("templateData", data);
        if (request.recipientType != null) mailPayload.put("recipientType", request.recipientType);
        if (request.recipientId != null) mailPayload.put("recipientId", request.recipientId);

        return notificationService.email(mailPayload);
    }

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
}
