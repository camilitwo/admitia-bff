package cl.mtn.admitiabff.service.notification.template;

import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import java.util.Map;

/**
 * Contrato del patrón Strategy para renderizar el cuerpo HTML de un email.
 * <p>
 * Cada template del enum {@link EmailTemplate} debería tener una implementación
 * registrada como {@code @Component}. El {@link EmailTemplateRegistry} las
 * descubre automáticamente y enruta por {@link #template()}.
 */
public interface EmailTemplateRenderer {

    /** Identifica a qué entrada del enum responde este renderer. */
    EmailTemplate template();

    /**
     * Renderiza el cuerpo HTML usando los datos del contrato.
     *
     * @param data datos arbitrarios enviados desde el front (campos como
     *             {@code recipientName}, {@code applicationId}, {@code newStatus}, etc.).
     * @return HTML completo listo para enviar a Resend.
     */
    String render(Map<String, Object> data);

    /**
     * Asunto específico para esta combinación template+datos.
     * Por defecto usa el {@link EmailTemplate#getDefaultSubject()}.
     */
    default String subject(Map<String, Object> data) {
        return template().getDefaultSubject();
    }
}

