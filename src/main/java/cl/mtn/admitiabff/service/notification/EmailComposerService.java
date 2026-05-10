package cl.mtn.admitiabff.service.notification;

import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import cl.mtn.admitiabff.service.NotificationService;
import cl.mtn.admitiabff.service.notification.template.EmailTemplateRegistry;
import cl.mtn.admitiabff.service.notification.template.EmailTemplateRenderer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * Orquestador central para envío de emails con plantilla.
 *
 * <p>Patrón aplicado: <b>Strategy + Registry (coreografía dirigida por enum)</b>.
 * <ul>
 *   <li>El caller (controller o service) construye un {@link EmailRequest} indicando
 *       {@code template} (mandatorio: enum {@link EmailTemplate}), {@code to}, y un
 *       {@code Map<String,Object> data} con las variables del template.</li>
 *   <li>Este service resuelve el {@link EmailTemplateRenderer} en el
 *       {@link EmailTemplateRegistry} y obtiene el HTML final + subject.</li>
 *   <li>Delega el envío real a {@link NotificationService#email(Map)} que persiste
 *       el registro y despacha vía Resend.</li>
 * </ul>
 *
 * <p>De esta forma <b>un solo punto</b> arma el correo completo, y todas las
 * integraciones (auth, application, interview, evaluation, institucional)
 * lo reutilizan.
 */
@Service
public class EmailComposerService {

    private static final Logger log = LoggerFactory.getLogger(EmailComposerService.class);

    private final EmailTemplateRegistry registry;
    private final NotificationService notificationService;
    private final SpringTemplateEngine templateEngine;

    public EmailComposerService(EmailTemplateRegistry registry,
                                @Lazy NotificationService notificationService,
                                SpringTemplateEngine templateEngine) {
        this.registry = registry;
        this.notificationService = notificationService;
        this.templateEngine = templateEngine;
    }

    /**
     * Punto de entrada principal. Lee el campo {@code template} del payload
     * (también acepta {@code templateName} por retro-compatibilidad), arma el
     * HTML y dispara el envío a través de {@link NotificationService}.
     *
     * @param payload contrato del caller con al menos {@code template} y {@code to}.
     * @return respuesta de NotificationService (success/data/notification id).
     */

    public Map<String, Object> sendFromPayload(Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload requerido");
        String rawTemplate = firstNonBlank(
                (String) payload.get("templates"),
                (String) payload.get("templateName"),
                (String) payload.get("type") // último fallback: el "type" suele coincidir con el enum
        );
        EmailTemplate template = EmailTemplate.from(rawTemplate);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = payload.get("data") instanceof Map
                ? (Map<String, Object>) payload.get("data")
                : payload; // si el front no anida, usamos el payload completo como data

        return null;
        /*return send(EmailRequest.builder()
                .template(template)
                .to(String.valueOf(payload.get("to")))
                .subject((String) payload.get("subject"))
                .recipientType((String) payload.get("recipientType"))
                .recipientId(payload.get("recipientId") instanceof Number n ? n.longValue() : null)
                .data(data)
                .build());*/
    }

    /** API tipada para invocar desde otros services sin pasar por payload Map. */
    public Map<String, Object> send(EmailRequestDTO request) {
        Objects.requireNonNull(request.template, "template requerido");
        if (request.to == null || request.to.isBlank() || "null".equalsIgnoreCase(request.to)) {
            throw new IllegalArgumentException(
                    "Destinatario (to) requerido y no puede estar vacío. Resolverlo desde el front o desde la base de datos antes de invocar al composer.");
        }

        //EmailTemplateRenderer renderer = registry.resolve(request.template);
        Map<String, Object> data = request.data == null ? Map.of() : request.data;

        Context ctx = new Context();
        ctx.setVariables(data);
        String html = templateEngine.process("templateCorreo", ctx);

        EmailTemplate templateEnum = null;
        try {
            templateEnum = EmailTemplate.from(request.template);
        } catch (Exception ignored) {
            // template no mapeable a enum, usaremos fallback de subject
        }

        String subject = firstNonBlank(
                request.subject,
                templateEnum != null ? templateEnum.getDefaultSubject() : null,
                "Notificación MTN");

        log.debug("Email compose template={} to={} subject={}", request.template, request.to, subject);

        Map<String, Object> mailPayload = new LinkedHashMap<>();
        mailPayload.put("to", request.to);
        mailPayload.put("subject", subject);
        mailPayload.put("message", html); // HTML final renderizado
        //mailPayload.put("type", request.template.name());
        //mailPayload.put("templateName", request.template.name());
        mailPayload.put("templateData", data);
        if (request.recipientType != null) mailPayload.put("recipientType", request.recipientType);
        if (request.recipientId != null) mailPayload.put("recipientId", request.recipientId);

        return notificationService.email(mailPayload);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    // -------------------------------------------------------------
    // Request DTO
    // -------------------------------------------------------------

}

