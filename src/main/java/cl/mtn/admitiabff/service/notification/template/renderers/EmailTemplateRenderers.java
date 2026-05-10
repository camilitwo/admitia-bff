package cl.mtn.admitiabff.service.notification.template.renderers;

import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import cl.mtn.admitiabff.service.notification.template.EmailLayout;
import cl.mtn.admitiabff.service.notification.template.EmailTemplateRenderer;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renderers concretos para cada {@link EmailTemplate}. Cada clase es un
 * {@code @Component} y se auto-registra en {@code EmailTemplateRegistry}.
 *
 * <p>Mantener uno por archivo es más limpio, pero los agrupamos aquí como
 * clases públicas para evitar explosión de archivos. Cada uno se descubre
 * por separado al ser {@code @Component}.
 */
public final class EmailTemplateRenderers {

    private EmailTemplateRenderers() {}

    // ============================ AUTH ============================

    @Component
    public static class WelcomeRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.WELCOME; }
        @Override public String render(Map<String, Object> data) {
            String name = EmailLayout.str(data, "recipientName", "Bienvenido/a");
            String body = EmailLayout.heading("¡Hola " + name + "!")
                    + EmailLayout.paragraph("Tu cuenta en el sistema de admisión MTN se creó correctamente.")
                    + EmailLayout.paragraph("Desde ahora podrás hacer seguimiento de la postulación, subir documentos y agendar entrevistas.");
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class EmailVerificationRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.EMAIL_VERIFICATION; }
        @Override public String render(Map<String, Object> data) {
            String code = EmailLayout.str(data, "code", "------");
            String body = EmailLayout.heading("Verifica tu correo")
                    + EmailLayout.paragraph("Ingresa el siguiente código en la plataforma para confirmar tu correo electrónico:")
                    + EmailLayout.callout("<div style=\"font-size:28px;letter-spacing:8px;font-weight:bold;text-align:center;color:#0d3b66;\">"
                        + EmailLayout.escape(code) + "</div>")
                    + EmailLayout.paragraph("El código expira en 15 minutos.");
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class EmailVerificationLinkRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.EMAIL_VERIFICATION_LINK; }
        @Override public String render(Map<String, Object> data) {
            String name = EmailLayout.str(data, "recipientName", "");
            String link = EmailLayout.str(data, "verificationLink", "#");
            String greeting = name.isBlank() ? "¡Hola!" : "¡Hola " + name + "!";
            String body = EmailLayout.heading("Verifica tu correo electrónico")
                    + EmailLayout.paragraph(greeting)
                    + EmailLayout.paragraph("Gracias por registrarte en el sistema de admisión MTN. "
                        + "Para completar la activación de tu cuenta, confirma tu correo electrónico haciendo clic en el siguiente botón:")
                    + "<p style=\"text-align:center;margin:24px 0;\"><a href=\"" + EmailLayout.escape(link) + "\" "
                    + "style=\"background:#0d3b66;color:#fff;padding:12px 24px;border-radius:6px;text-decoration:none;font-weight:bold;\">Verificar mi correo</a></p>"
                    + EmailLayout.paragraph("Si el botón no funciona, copia y pega esta URL en tu navegador:")
                    + EmailLayout.paragraph("<span style=\"word-break:break-all;color:#0d3b66;\">" + EmailLayout.escape(link) + "</span>")
                    + EmailLayout.paragraph("Si no creaste esta cuenta, puedes ignorar este mensaje.");
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class PasswordResetRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.PASSWORD_RESET; }
        @Override public String render(Map<String, Object> data) {
            String link = EmailLayout.str(data, "resetLink", "#");
            String body = EmailLayout.heading("Recuperar contraseña")
                    + EmailLayout.paragraph("Recibimos una solicitud para restablecer tu contraseña. Haz clic en el botón:")
                    + "<p style=\"text-align:center;margin:24px 0;\"><a href=\"" + EmailLayout.escape(link) + "\" "
                    + "style=\"background:#0d3b66;color:#fff;padding:12px 24px;border-radius:6px;text-decoration:none;\">Restablecer contraseña</a></p>"
                    + EmailLayout.paragraph("Si no solicitaste este cambio, ignora este mensaje.");
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class PasswordChangedRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.PASSWORD_CHANGED; }
        @Override public String render(Map<String, Object> data) {
            String body = EmailLayout.heading("Contraseña actualizada")
                    + EmailLayout.paragraph("Tu contraseña fue cambiada correctamente. Si no fuiste tú, contacta a soporte de inmediato.");
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class UserInvitationRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.USER_INVITATION; }
        @Override public String render(Map<String, Object> data) {
            String role = EmailLayout.str(data, "role", "usuario");
            String link = EmailLayout.str(data, "invitationLink", "#");
            String body = EmailLayout.heading("Invitación a la plataforma MTN")
                    + EmailLayout.paragraph("Has sido invitado a unirte como " + role + ". Activa tu cuenta:")
                    + "<p style=\"text-align:center;margin:24px 0;\"><a href=\"" + EmailLayout.escape(link)
                    + "\" style=\"background:#0d3b66;color:#fff;padding:12px 24px;border-radius:6px;text-decoration:none;\">Activar cuenta</a></p>";
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    // ========================= APPLICATION =========================

    @Component
    public static class ApplicationReceivedRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.APPLICATION_RECEIVED; }
        @Override public String render(Map<String, Object> data) {
            String studentName = EmailLayout.str(data, "studentName", "el postulante");
            String applicationId = EmailLayout.str(data, "applicationId", "");
            String body = EmailLayout.heading("Postulación recibida")
                    + EmailLayout.paragraph("Hemos recibido la postulación de " + studentName + ".")
                    + (applicationId.isEmpty() ? "" : EmailLayout.callout("<strong>N° de postulación:</strong> " + EmailLayout.escape(applicationId)))
                    + EmailLayout.paragraph("Pronto te contactaremos con los siguientes pasos del proceso de admisión.");
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class DocumentReviewRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.DOCUMENT_REVIEW; }
        @Override public String render(Map<String, Object> data) {
            String result = EmailLayout.str(data, "reviewResult", "revisado");
            String notes = EmailLayout.str(data, "notes", "");
            String body = EmailLayout.heading("Revisión de documentos")
                    + EmailLayout.paragraph("El estado de la revisión de tus documentos es: <strong>" + EmailLayout.escape(result) + "</strong>.")
                    + (notes.isBlank() ? "" : EmailLayout.callout(EmailLayout.escape(notes)));
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class DocumentReminderRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.DOCUMENT_REMINDER; }
        @Override public String render(Map<String, Object> data) {
            String pending = EmailLayout.str(data, "pendingDocuments", "documentos pendientes");
            String body = EmailLayout.heading("Documentos pendientes")
                    + EmailLayout.paragraph("Tu postulación tiene documentos pendientes:")
                    + EmailLayout.callout(EmailLayout.escape(pending))
                    + EmailLayout.paragraph("Por favor súbelos a la brevedad para no retrasar el proceso.");
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class StatusUpdateRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.STATUS_UPDATE; }
        @Override public String render(Map<String, Object> data) {
            String newStatus = EmailLayout.str(data, "newStatus", "actualizado");
            String notes = EmailLayout.str(data, "notes", "");
            String body = EmailLayout.heading("Actualización de tu postulación")
                    + EmailLayout.paragraph("El estado de tu postulación cambió a: <strong>" + EmailLayout.escape(newStatus) + "</strong>.")
                    + (notes.isBlank() ? "" : EmailLayout.callout(EmailLayout.escape(notes)));
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class AdmissionResultRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.ADMISSION_RESULT; }
        @Override public String render(Map<String, Object> data) {
            String result = EmailLayout.str(data, "result", "");
            String body = EmailLayout.heading("Resultado de admisión")
                    + EmailLayout.paragraph("El proceso de admisión finalizó.")
                    + (result.isBlank() ? "" : EmailLayout.callout("<strong>Resultado:</strong> " + EmailLayout.escape(result)))
                    + EmailLayout.paragraph("Revisa la plataforma para ver el detalle.");
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    // ========================== INTERVIEW ==========================

    @Component
    public static class InterviewInvitationRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.INTERVIEW_INVITATION; }
        @Override public String render(Map<String, Object> data) {
            String date = EmailLayout.str(data, "scheduledDate", "");
            String interviewer = EmailLayout.str(data, "interviewerName", "");
            String body = EmailLayout.heading("Invitación a entrevista")
                    + EmailLayout.paragraph("Te invitamos a la entrevista del proceso de admisión.")
                    + EmailLayout.callout(
                        (date.isBlank() ? "" : "<strong>Fecha:</strong> " + EmailLayout.escape(date) + "<br/>")
                        + (interviewer.isBlank() ? "" : "<strong>Entrevistador:</strong> " + EmailLayout.escape(interviewer)));
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class InterviewRescheduledRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.INTERVIEW_RESCHEDULED; }
        @Override public String render(Map<String, Object> data) {
            String date = EmailLayout.str(data, "newDate", "");
            String body = EmailLayout.heading("Entrevista reprogramada")
                    + EmailLayout.paragraph("Tu entrevista fue reprogramada.")
                    + (date.isBlank() ? "" : EmailLayout.callout("<strong>Nueva fecha:</strong> " + EmailLayout.escape(date)));
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class InterviewCancelledRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.INTERVIEW_CANCELLED; }
        @Override public String render(Map<String, Object> data) {
            String reason = EmailLayout.str(data, "reason", "");
            String body = EmailLayout.heading("Entrevista cancelada")
                    + EmailLayout.paragraph("Lamentamos informar que tu entrevista fue cancelada.")
                    + (reason.isBlank() ? "" : EmailLayout.callout("<strong>Motivo:</strong> " + EmailLayout.escape(reason)));
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class InterviewSummaryRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.INTERVIEW_SUMMARY; }
        @Override public String render(Map<String, Object> data) {
            String applicationId = EmailLayout.str(data, "applicationId", "");
            String summary = EmailLayout.str(data, "summary", "Adjuntamos el resumen de las entrevistas realizadas.");
            String body = EmailLayout.heading("Resumen de entrevistas")
                    + (applicationId.isBlank() ? "" : EmailLayout.paragraph("Postulación N° " + applicationId))
                    + EmailLayout.callout(EmailLayout.escape(summary));
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    // ========================== EVALUATION =========================

    @Component
    public static class EvaluationAssignmentRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.EVALUATION_ASSIGNMENT; }
        @Override public String render(Map<String, Object> data) {
            String evaluator = EmailLayout.str(data, "evaluatorName", "evaluador/a");
            String subject = EmailLayout.str(data, "evaluationSubject", "");
            String body = EmailLayout.heading("Nueva evaluación asignada")
                    + EmailLayout.paragraph("Hola " + evaluator + ", se te ha asignado una nueva evaluación.")
                    + (subject.isBlank() ? "" : EmailLayout.callout("<strong>Materia:</strong> " + EmailLayout.escape(subject)));
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class EvaluationCompletedRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.EVALUATION_COMPLETED; }
        @Override public String render(Map<String, Object> data) {
            String body = EmailLayout.heading("Evaluación completada")
                    + EmailLayout.paragraph("Una evaluación del proceso de admisión fue cerrada. Revisa la plataforma para ver el detalle.");
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class EvaluationRescheduledRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.EVALUATION_RESCHEDULED; }
        @Override public String render(Map<String, Object> data) {
            String date = EmailLayout.str(data, "newDate", "");
            String body = EmailLayout.heading("Evaluación reprogramada")
                    + EmailLayout.paragraph("La evaluación fue reprogramada.")
                    + (date.isBlank() ? "" : EmailLayout.callout("<strong>Nueva fecha:</strong> " + EmailLayout.escape(date)));
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    @Component
    public static class EvaluationCancelledRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.EVALUATION_CANCELLED; }
        @Override public String render(Map<String, Object> data) {
            String reason = EmailLayout.str(data, "reason", "");
            String body = EmailLayout.heading("Evaluación cancelada")
                    + EmailLayout.paragraph("La evaluación fue cancelada.")
                    + (reason.isBlank() ? "" : EmailLayout.callout("<strong>Motivo:</strong> " + EmailLayout.escape(reason)));
            return EmailLayout.wrap(template().getDefaultSubject(), body);
        }
    }

    // =========================== SYSTEM ============================

    @Component
    public static class GenericRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.GENERIC; }
        @Override public String render(Map<String, Object> data) {
            String message = EmailLayout.str(data, "message", "");
            return EmailLayout.wrap(template().getDefaultSubject(),
                    EmailLayout.heading("Notificación MTN") + EmailLayout.paragraph(message));
        }
        @Override public String subject(Map<String, Object> data) {
            return EmailLayout.str(data, "subject", template().getDefaultSubject());
        }
    }

    /**
     * Renderer para emails en TEXTO PLANO (sin layout HTML). Útil cuando el
     * caller ya tiene el contenido listo (texto puro) y no quiere envolverlo
     * en el layout institucional. {@code ResendEmailSender} detecta que no es
     * HTML y lo envía como {@code text} en la API de Resend.
     */
    @Component
    public static class PlainTextRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.PLAIN_TEXT; }
        @Override public String render(Map<String, Object> data) {
            // Acepta "body", "message" o "text" como campo del contenido.
            String body = EmailLayout.str(data, "body", null);
            if (body == null) body = EmailLayout.str(data, "message", null);
            if (body == null) body = EmailLayout.str(data, "text", "");
            return body; // se envía tal cual, sin tags HTML
        }
        @Override public String subject(Map<String, Object> data) {
            return EmailLayout.str(data, "subject", template().getDefaultSubject());
        }
    }

    @Component
    public static class TestRenderer implements EmailTemplateRenderer {
        @Override public EmailTemplate template() { return EmailTemplate.TEST; }
        @Override public String render(Map<String, Object> data) {
            String message = EmailLayout.str(data, "message", "Correo de prueba del sistema MTN");
            return EmailLayout.wrap(template().getDefaultSubject(),
                    EmailLayout.heading("Test") + EmailLayout.paragraph(message));
        }
    }
}

