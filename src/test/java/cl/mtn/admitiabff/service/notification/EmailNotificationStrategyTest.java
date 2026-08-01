package cl.mtn.admitiabff.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.notification.NotificationEntity;
import cl.mtn.admitiabff.util.JsonSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailNotificationStrategyTest {
    @Mock ResendEmailSender resendEmailSender;
    @Mock JsonSupport jsonSupport;

    @Test
    void sensitiveEmailDispatchesSecretButPersistsOnlyRedactedContent() {
        when(jsonSupport.write(Map.of("redacted", true))).thenReturn("{\"redacted\":true}");
        EmailNotificationStrategy strategy = new EmailNotificationStrategy(resendEmailSender, jsonSupport, false);

        NotificationEntity notification = strategy.createNotification(Map.of(
            "to", "persona@cmtn.cl",
            "subject", "Contraseña temporal",
            "message", "<p>Clave: Secreta9!</p>",
            "templateData", Map.of("temporaryPassword", "Secreta9!"),
            "sensitive", true
        ));

        assertThat(notification.getMessage()).isEqualTo("[CONTENIDO SENSIBLE OMITIDO]");
        assertThat(notification.getTemplateData()).isEqualTo("{\"redacted\":true}");
        assertThat(notification.getDispatchMessage()).contains("Secreta9!");

        strategy.dispatch(notification);

        verify(resendEmailSender).send("persona@cmtn.cl", "Contraseña temporal", "<p>Clave: Secreta9!</p>");
        assertThat(notification.getDispatchMessage()).isNull();
        assertThat(notification.getMessage()).doesNotContain("Secreta9!");
        assertThat(notification.getTemplateData()).doesNotContain("Secreta9!");
    }
}
