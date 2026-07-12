package cl.mtn.admitiabff.service.notification.template;

import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Registro auto-descubierto de {@link EmailTemplateRenderer}. Spring inyecta
 * todos los beans que implementan la interfaz y este registro los agrupa por
 * {@link EmailTemplate}, evitando ifs/switch en el composer.
 *
 * <p>Si un template no tiene renderer dedicado, se cae al {@code GENERIC}.
 */
@Component
public class EmailTemplateRegistry {

    private final Map<EmailTemplate, EmailTemplateRenderer> renderers = new EnumMap<>(EmailTemplate.class);
    private final EmailTemplateRenderer fallback;

    public EmailTemplateRegistry(List<EmailTemplateRenderer> beans) {
        EmailTemplateRenderer generic = null;
        for (EmailTemplateRenderer bean : beans) {
            EmailTemplate key = bean.template();
            if (renderers.containsKey(key)) {
                throw new IllegalStateException("Renderer duplicado para template " + key + ": " + bean.getClass());
            }
            renderers.put(key, bean);
            if (key == EmailTemplate.GENERIC) {
                generic = bean;
            }
        }
        if (generic == null) {
            throw new IllegalStateException("Falta el renderer GENERIC (EmailTemplate.GENERIC).");
        }
        this.fallback = generic;
    }

    public EmailTemplateRenderer resolve(EmailTemplate template) {
        return renderers.getOrDefault(template, fallback);
    }
}

