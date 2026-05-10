package cl.mtn.admitiabff.util;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Devuelve el FRAGMENTO HTML (body) que reemplaza el placeholder
 * {@code {{html_replace}}} dentro del layout base
 * {@code src/main/resources/template/templateCorreo.html}.
 *
 * <p>Soporta:
 * <ul>
 *   <li>Interpolación simple {@code {{variable}}}.</li>
 *   <li>Render de listas con {@code {{#each clave}} ... {{/each}}} usando
 *       {@code {{campo}}} dentro del bloque para cada item.</li>
 * </ul>
 * Nunca retorna null.
 */
public final class TemplateUtils {

    private TemplateUtils() {}

    public static String generateTemplate(String flow) {
        return generateTemplate(flow, Map.of());
    }

    public static String generateTemplate(String flow, Map<String, Object> data) {
        String body = bodyFor(flow);
        return render(body, data == null ? Map.of() : data);
    }

    // ------------------------------------------------------------------
    // Plantillas por flow
    // ------------------------------------------------------------------
    private static String bodyFor(String flow) {
        String key = flow == null ? "" : flow.trim().toLowerCase();
        return switch (key) {
            // Interview / application summary
            case "summary", "interview_summary"      -> SUMMARY_BODY;

            // Application
            case "application_received"              -> APPLICATION_RECEIVED_BODY;
            case "document_review"                   -> DOCUMENT_REVIEW_BODY;
            case "document_reminder"                 -> DOCUMENT_REMINDER_BODY;
            case "status_update"                     -> STATUS_UPDATE_BODY;
            case "admission_result"                  -> ADMISSION_RESULT_BODY;

            // Interview
            case "interview", "interview_invitation" -> INTERVIEW_INVITATION_BODY;
            case "interview_rescheduled"             -> INTERVIEW_RESCHEDULED_BODY;
            case "interview_cancelled"               -> INTERVIEW_CANCELLED_BODY;

            // Evaluation
            case "evaluation_assignment", "evaluation" -> EVALUATION_ASSIGNMENT_BODY;
            case "evaluation_completed"              -> EVALUATION_COMPLETED_BODY;
            case "evaluation_rescheduled"            -> EVALUATION_RESCHEDULED_BODY;
            case "evaluation_cancelled"              -> EVALUATION_CANCELLED_BODY;

            // Auth
            case "welcome"                           -> WELCOME_BODY;
            case "verification", "email_verification" -> VERIFICATION_BODY;
            case "email_verification_link"           -> EMAIL_VERIFICATION_LINK_BODY;
            case "password_reset"                    -> PASSWORD_RESET_BODY;
            case "password_changed"                  -> PASSWORD_CHANGED_BODY;
            case "user_invitation"                   -> USER_INVITATION_BODY;

            // System
            case "test"                              -> TEST_BODY;
            case "plain_text"                        -> PLAIN_TEXT_BODY;
            default                                  -> GENERIC_BODY;
        };
    }

    private static final String SUMMARY_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Estimados {{parentNames}}</h2>

                <p style="margin:0 0 14px 0;">
                  Junto con saludarlos cordialmente, queremos acompañarlos en
                  esta importante etapa del proceso de admisión y compartirles
                  un resumen de las entrevistas que hemos coordinado para su
                  familia.
                </p>

                <p style="margin:0 0 18px 0;">
                  A continuación encontrarán el detalle de las instancias
                  agendadas para <strong>{{studentName}}</strong>, postulante
                  a <strong>{{gradeApplied}}</strong> en el Colegio Monte Tabor
                  y Nazaret.
                </p>

                <div class="info-box" style="background:#f8f9fa;padding:15px 20px;border-radius:6px;margin-bottom:25px;border-left:4px solid #ff9e18;">
                  <strong>N° de postulación:</strong> {{applicationId}}<br/>
                  <strong>Total de entrevistas agendadas:</strong> {{totalInterviews}}
                </div>

                <h3 style="color:#273b7a;margin:0 0 12px 0;font-size:17px;">Detalle de entrevistas</h3>

                <table width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="border-collapse:collapse;margin-bottom:20px;font-size:14px;">
                  <thead>
                    <tr style="background:#273b7a;color:#fff;">
                      <th align="left" style="padding:10px;">Tipo</th>
                      <th align="left" style="padding:10px;">Fecha</th>
                      <th align="left" style="padding:10px;">Hora</th>
                      <th align="left" style="padding:10px;">Modalidad</th>
                      <th align="left" style="padding:10px;">Lugar</th>
                      <th align="left" style="padding:10px;">A cargo de</th>
                    </tr>
                  </thead>
                  <tbody>
                    {{#each interviews}}
                    <tr style="border-bottom:1px solid #e5e7eb;">
                      <td style="padding:10px;"><strong>{{interviewType}}</strong></td>
                      <td style="padding:10px;">{{scheduledDate}}</td>
                      <td style="padding:10px;">{{scheduledTime}}</td>
                      <td style="padding:10px;">{{mode}}</td>
                      <td style="padding:10px;">{{location}}</td>
                      <td style="padding:10px;">{{interviewerName}}{{secondInterviewerSuffix}}</td>
                    </tr>
                    {{/each}}
                  </tbody>
                </table>

                <p style="margin:18px 0 8px 0;">
                  Si alguna fecha u horario presenta inconvenientes, o requieren
                  reagendar alguna entrevista, no duden en escribirnos a
                  <a href="mailto:admision@mtn.cl" style="color:#273b7a;">admision@mtn.cl</a>
                  o responder directamente este correo. Estaremos atentos para
                  ayudarles en lo que necesiten.
                </p>

                <p style="margin:18px 0 0 0;">
                  Agradecemos sinceramente la confianza depositada en nuestro
                  colegio y quedamos a su disposición para acompañarles durante
                  todo el proceso.
                </p>

                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String WELCOME_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Estimados {{parentNames}}</h2>
                <p style="margin:0 0 14px 0;">
                  Junto con saludarles, queremos darles la bienvenida al
                  Colegio Monte Tabor y Nazaret. Es un gusto recibirles en
                  nuestra comunidad y poder acompañarles desde este primer paso.
                </p>
                <p style="margin:0 0 14px 0;">
                  Su cuenta ha sido creada exitosamente con el correo
                  <strong>{{email}}</strong>. Desde su panel podrán completar
                  la postulación, cargar documentos y revisar el avance del
                  proceso en cualquier momento.
                </p>
                <p style="margin:18px 0 0 0;">
                  Cualquier consulta, no duden en escribirnos a
                  <a href="mailto:admision@mtn.cl" style="color:#273b7a;">admision@mtn.cl</a>.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String VERIFICATION_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Verificación de su correo</h2>
                <p style="margin:0 0 14px 0;">
                  Hemos recibido una solicitud para verificar su correo
                  electrónico en la plataforma de admisión MTN.
                </p>
                <p style="margin:0 0 8px 0;">Su código de verificación es:</p>
                <div class="info-box" style="background:#f8f9fa;padding:18px 20px;border-radius:6px;margin:0 0 18px 0;border-left:4px solid #ff9e18;text-align:center;">
                  <span style="font-size:26px;letter-spacing:6px;color:#273b7a;font-weight:bold;">{{code}}</span>
                </div>
                <p style="margin:0 0 14px 0;">
                  Este código expira en <strong>{{expiresInMinutes}} minutos</strong>.
                  Si usted no solicitó esta verificación, puede ignorar este mensaje.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String EMAIL_VERIFICATION_LINK_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Verifique su correo electrónico</h2>
                <p style="margin:0 0 14px 0;">Estimado/a {{recipientName}},</p>
                <p style="margin:0 0 14px 0;">
                  Gracias por registrarse en el sistema de admisión del Colegio
                  Monte Tabor y Nazaret. Para completar la activación de su
                  cuenta, por favor confirme su correo electrónico haciendo clic
                  en el siguiente botón:
                </p>
                <p style="margin:0 0 18px 0;text-align:center;">
                  <a href="{{verificationLink}}" class="button"
                     style="display:inline-block;background:#ff9e18;color:#fff;text-decoration:none;padding:14px 28px;border-radius:4px;font-weight:bold;font-size:15px;">
                    Verificar mi correo
                  </a>
                </p>
                <p style="margin:0 0 8px 0;color:#666;font-size:13px;">
                  Si el botón no funciona, copie y pegue la siguiente URL en su navegador:
                </p>
                <p style="margin:0 0 18px 0;word-break:break-all;font-size:13px;">
                  <a href="{{verificationLink}}" style="color:#273b7a;">{{verificationLink}}</a>
                </p>
                <p style="margin:0 0 14px 0;color:#666;font-size:13px;">
                  Si usted no creó esta cuenta, puede ignorar este mensaje con
                  tranquilidad.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String PASSWORD_RESET_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Recuperación de contraseña</h2>
                <p style="margin:0 0 14px 0;">
                  Hemos recibido una solicitud para restablecer la contraseña
                  asociada a su cuenta. Para continuar, utilice el siguiente
                  enlace:
                </p>
                <p style="margin:0 0 18px 0;">
                  <a href="{{resetUrl}}" class="button"
                     style="display:inline-block;background:#ff9e18;color:#fff;text-decoration:none;padding:12px 24px;border-radius:4px;font-weight:bold;">
                    Restablecer contraseña
                  </a>
                </p>
                <p style="margin:0 0 14px 0;color:#666;font-size:13px;">
                  Si usted no realizó esta solicitud, puede ignorar este mensaje
                  con tranquilidad. Su contraseña actual seguirá vigente.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String PASSWORD_CHANGED_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Su contraseña fue actualizada</h2>
                <p style="margin:0 0 14px 0;">
                  Le confirmamos que la contraseña de su cuenta ha sido
                  modificada correctamente.
                </p>
                <p style="margin:0 0 14px 0;">
                  Si usted no realizó este cambio, le pedimos contactarnos
                  cuanto antes a
                  <a href="mailto:admision@mtn.cl" style="color:#273b7a;">admision@mtn.cl</a>
                  para resguardar su cuenta.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String USER_INVITATION_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Estimado/a {{name}}</h2>
                <p style="margin:0 0 14px 0;">
                  Le damos la bienvenida a la plataforma del Colegio Monte
                  Tabor y Nazaret. Ha sido invitado/a a participar como
                  <strong>{{role}}</strong> dentro del proceso de admisión.
                </p>
                <p style="margin:0 0 18px 0;">
                  Para activar su cuenta, ingrese al siguiente enlace:
                </p>
                <p style="margin:0 0 18px 0;">
                  <a href="{{invitationUrl}}" class="button"
                     style="display:inline-block;background:#ff9e18;color:#fff;text-decoration:none;padding:12px 24px;border-radius:4px;font-weight:bold;">
                    Activar mi cuenta
                  </a>
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String APPLICATION_RECEIVED_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Estimados {{parentNames}}</h2>
                <p style="margin:0 0 14px 0;">
                  Junto con saludarles, queremos confirmarles que hemos
                  recibido correctamente la postulación de
                  <strong>{{studentName}}</strong> a
                  <strong>{{gradeApplied}}</strong>.
                </p>
                <div class="info-box" style="background:#f8f9fa;padding:15px 20px;border-radius:6px;margin:0 0 18px 0;border-left:4px solid #ff9e18;">
                  <strong>N° de postulación:</strong> {{applicationId}}<br/>
                  <strong>Fecha de recepción:</strong> {{receivedAt}}
                </div>
                <p style="margin:0 0 14px 0;">
                  En los próximos días revisaremos la documentación adjunta y
                  les iremos informando los siguientes pasos del proceso.
                  Pueden consultar el avance en cualquier momento desde su
                  panel de apoderado.
                </p>
                <p style="margin:18px 0 0 0;">
                  Cualquier duda, no duden en escribirnos a
                  <a href="mailto:admision@mtn.cl" style="color:#273b7a;">admision@mtn.cl</a>.
                  Estaremos atentos para acompañarles.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String DOCUMENT_REVIEW_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Estimados {{parentNames}}</h2>
                <p style="margin:0 0 14px 0;">
                  Les informamos que hemos completado la revisión de los
                  documentos asociados a la postulación de
                  <strong>{{studentName}}</strong> (postulación N°
                  <strong>{{applicationId}}</strong>).
                </p>
                <div class="info-box" style="background:#f8f9fa;padding:15px 20px;border-radius:6px;margin:0 0 18px 0;border-left:4px solid #ff9e18;">
                  <strong>Estado de la revisión:</strong> {{reviewStatus}}<br/>
                  <strong>Comentarios:</strong> {{comments}}
                </div>
                <p style="margin:0 0 14px 0;">
                  Si la revisión requiere acciones de su parte, podrán
                  visualizarlas en su panel y subir nuevamente lo que
                  corresponda. De lo contrario, continuaremos con la siguiente
                  etapa del proceso.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String DOCUMENT_REMINDER_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Estimados {{parentNames}}</h2>
                <p style="margin:0 0 14px 0;">
                  Junto con saludarles, queremos recordarles que aún quedan
                  documentos pendientes de carga en la postulación de
                  <strong>{{studentName}}</strong>.
                </p>
                <div class="info-box" style="background:#fff7ec;padding:15px 20px;border-radius:6px;margin:0 0 18px 0;border-left:4px solid #ff9e18;">
                  <strong>N° de postulación:</strong> {{applicationId}}<br/>
                  <strong>Documentos pendientes:</strong> {{pendingDocuments}}<br/>
                  <strong>Plazo sugerido:</strong> {{deadline}}
                </div>
                <p style="margin:0 0 14px 0;">
                  Pueden completar la carga directamente desde su panel de
                  apoderado. Si requieren apoyo o ya enviaron parte de la
                  documentación por otra vía, no duden en avisarnos.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String STATUS_UPDATE_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Estimados {{parentNames}}</h2>
                <p style="margin:0 0 14px 0;">
                  Queremos mantenerles al tanto del avance de la postulación
                  de <strong>{{studentName}}</strong>.
                </p>
                <div class="info-box" style="background:#f8f9fa;padding:15px 20px;border-radius:6px;margin:0 0 18px 0;border-left:4px solid #ff9e18;">
                  <strong>N° de postulación:</strong> {{applicationId}}<br/>
                  <strong>Estado anterior:</strong> {{previousStatus}}<br/>
                  <strong>Estado actual:</strong> {{currentStatus}}
                </div>
                <p style="margin:0 0 14px 0;">{{message}}</p>
                <p style="margin:18px 0 0 0;">
                  Pueden revisar el detalle completo en su panel. Cualquier
                  consulta, escríbannos a
                  <a href="mailto:admision@mtn.cl" style="color:#273b7a;">admision@mtn.cl</a>.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String ADMISSION_RESULT_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Estimados {{parentNames}}</h2>
                <p style="margin:0 0 14px 0;">
                  Junto con saludarles, queremos comunicarles el resultado
                  del proceso de admisión de
                  <strong>{{studentName}}</strong> al curso
                  <strong>{{gradeApplied}}</strong>.
                </p>
                <div class="info-box" style="background:#f8f9fa;padding:18px 20px;border-radius:6px;margin:0 0 18px 0;border-left:4px solid #ff9e18;">
                  <strong>N° de postulación:</strong> {{applicationId}}<br/>
                  <strong>Resultado:</strong> {{result}}
                </div>
                <p style="margin:0 0 14px 0;">{{message}}</p>
                <p style="margin:18px 0 0 0;">
                  Para conocer los pasos a seguir, pueden ingresar a su panel
                  o escribirnos directamente a
                  <a href="mailto:admision@mtn.cl" style="color:#273b7a;">admision@mtn.cl</a>.
                  Quedamos atentos a sus consultas.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String INTERVIEW_INVITATION_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Estimados {{parentNames}}</h2>
                <p style="margin:0 0 14px 0;">
                  Junto con saludarles, queremos invitarles a la entrevista
                  agendada como parte del proceso de admisión de
                  <strong>{{studentName}}</strong>.
                </p>
                <div class="info-box" style="background:#f8f9fa;padding:15px 20px;border-radius:6px;margin:0 0 18px 0;border-left:4px solid #ff9e18;">
                  <strong>Tipo de entrevista:</strong> {{interviewType}}<br/>
                  <strong>Fecha:</strong> {{scheduledDate}}<br/>
                  <strong>Hora:</strong> {{scheduledTime}}<br/>
                  <strong>Modalidad:</strong> {{mode}}<br/>
                  <strong>Lugar:</strong> {{location}}<br/>
                  <strong>A cargo de:</strong> {{interviewerName}}
                </div>
                <p style="margin:0 0 14px 0;">
                  Si la fecha u horario presenta inconvenientes, escríbannos
                  a <a href="mailto:admision@mtn.cl" style="color:#273b7a;">admision@mtn.cl</a>
                  o respondan este correo y coordinaremos una nueva instancia.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String INTERVIEW_RESCHEDULED_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Estimados {{parentNames}}</h2>
                <p style="margin:0 0 14px 0;">
                  Les informamos que la entrevista de
                  <strong>{{studentName}}</strong> ha sido reprogramada.
                </p>
                <div class="info-box" style="background:#f8f9fa;padding:15px 20px;border-radius:6px;margin:0 0 18px 0;border-left:4px solid #ff9e18;">
                  <strong>Tipo de entrevista:</strong> {{interviewType}}<br/>
                  <strong>Nueva fecha:</strong> {{scheduledDate}}<br/>
                  <strong>Nueva hora:</strong> {{scheduledTime}}<br/>
                  <strong>Modalidad:</strong> {{mode}}<br/>
                  <strong>Lugar:</strong> {{location}}
                </div>
                <p style="margin:0 0 14px 0;">
                  Lamentamos las molestias que esto pueda ocasionar y
                  agradecemos su comprensión. Si la nueva fecha no les acomoda,
                  no duden en avisarnos.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String INTERVIEW_CANCELLED_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Estimados {{parentNames}}</h2>
                <p style="margin:0 0 14px 0;">
                  Les informamos que la entrevista de
                  <strong>{{studentName}}</strong> agendada para el
                  <strong>{{scheduledDate}}</strong> a las
                  <strong>{{scheduledTime}}</strong> ha sido cancelada.
                </p>
                <p style="margin:0 0 14px 0;"><strong>Motivo:</strong> {{reason}}</p>
                <p style="margin:0 0 14px 0;">
                  En los próximos días les contactaremos para coordinar una
                  nueva fecha. Si necesitan agilizar este paso, escríbannos a
                  <a href="mailto:admision@mtn.cl" style="color:#273b7a;">admision@mtn.cl</a>.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String EVALUATION_ASSIGNMENT_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Estimado/a {{evaluatorName}}</h2>
                <p style="margin:0 0 14px 0;">
                  Le informamos que se le ha asignado una nueva evaluación
                  dentro del proceso de admisión.
                </p>
                <div class="info-box" style="background:#f8f9fa;padding:15px 20px;border-radius:6px;margin:0 0 18px 0;border-left:4px solid #ff9e18;">
                  <strong>Postulante:</strong> {{studentName}}<br/>
                  <strong>Curso al que postula:</strong> {{gradeApplied}}<br/>
                  <strong>Tipo de evaluación:</strong> {{evaluationType}}<br/>
                  <strong>Plazo:</strong> {{deadline}}
                </div>
                <p style="margin:0 0 14px 0;">
                  Puede acceder al detalle y registrar el resultado desde su
                  panel de evaluador. Cualquier consulta operativa, escríbanos
                  a <a href="mailto:admision@mtn.cl" style="color:#273b7a;">admision@mtn.cl</a>.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String EVALUATION_COMPLETED_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Evaluación completada</h2>
                <p style="margin:0 0 14px 0;">
                  Le confirmamos que la evaluación de
                  <strong>{{studentName}}</strong> ha sido registrada
                  correctamente.
                </p>
                <div class="info-box" style="background:#f8f9fa;padding:15px 20px;border-radius:6px;margin:0 0 18px 0;border-left:4px solid #ff9e18;">
                  <strong>Tipo de evaluación:</strong> {{evaluationType}}<br/>
                  <strong>Evaluador:</strong> {{evaluatorName}}<br/>
                  <strong>Fecha:</strong> {{completedAt}}
                </div>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String EVALUATION_RESCHEDULED_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Evaluación reprogramada</h2>
                <p style="margin:0 0 14px 0;">
                  Le informamos que la evaluación de
                  <strong>{{studentName}}</strong> ha sido reprogramada para
                  el <strong>{{scheduledDate}}</strong> a las
                  <strong>{{scheduledTime}}</strong>.
                </p>
                <p style="margin:0 0 14px 0;">
                  Cualquier inconveniente con la nueva fecha, no dude en
                  avisarnos.
                </p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String EVALUATION_CANCELLED_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Evaluación cancelada</h2>
                <p style="margin:0 0 14px 0;">
                  Le informamos que la evaluación de
                  <strong>{{studentName}}</strong> agendada para el
                  <strong>{{scheduledDate}}</strong> ha sido cancelada.
                </p>
                <p style="margin:0 0 14px 0;"><strong>Motivo:</strong> {{reason}}</p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String TEST_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Correo de prueba</h2>
                <p style="margin:0 0 14px 0;">
                  Este es un correo de prueba enviado desde la plataforma de
                  admisión MTN. Si lo recibió, la configuración de envío está
                  funcionando correctamente.
                </p>
                <p style="margin:0 0 14px 0;">
                  <strong>Mensaje:</strong> {{message}}
                </p>
                <p style="margin:24px 0 0 0;">
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    private static final String PLAIN_TEXT_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <p style="margin:0;white-space:pre-line;">{{message}}</p>
              </td>
            </tr>
            """;

    private static final String GENERIC_BODY = """
            <tr>
              <td class="content" style="padding:40px 30px;color:#333;line-height:1.7;font-family:'Segoe UI',Arial,sans-serif;font-size:15px;">
                <h2 style="color:#273b7a;margin:0 0 16px 0;font-size:22px;">Notificación MTN</h2>
                <p style="margin:0 0 14px 0;">{{message}}</p>
                <p style="margin:24px 0 0 0;">
                  Un cordial saludo,<br/>
                  <strong>Equipo de Admisión</strong><br/>
                  Colegio Monte Tabor y Nazaret
                </p>
              </td>
            </tr>
            """;

    // ------------------------------------------------------------------
    // Render con soporte de bloques {{#each ...}}{{/each}} e interpolación.
    // ------------------------------------------------------------------
    private static String render(String template, Map<String, Object> data) {
        if (template == null) return "";
        String out = expandEachBlocks(template, data);
        out = interpolate(out, data);
        // Limpia placeholders {{...}} que quedaron sin valor (excepto {{html_replace}}
        // que es resuelto luego por EmailComposerService).
        out = out.replaceAll("\\{\\{(?!html_replace)[^{}]+\\}\\}", "—");
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String expandEachBlocks(String template, Map<String, Object> data) {
        StringBuilder result = new StringBuilder(template.length());
        int cursor = 0;
        while (true) {
            int openStart = template.indexOf("{{#each ", cursor);
            if (openStart < 0) {
                result.append(template, cursor, template.length());
                break;
            }
            int openEnd = template.indexOf("}}", openStart);
            if (openEnd < 0) {
                result.append(template, cursor, template.length());
                break;
            }
            String key = template.substring(openStart + "{{#each ".length(), openEnd).trim();
            int blockStart = openEnd + 2;
            String closeTag = "{{/each}}";
            int blockEnd = template.indexOf(closeTag, blockStart);
            if (blockEnd < 0) {
                result.append(template, cursor, template.length());
                break;
            }
            String inner = template.substring(blockStart, blockEnd);

            // Append todo lo previo al bloque
            result.append(template, cursor, openStart);

            Object listObj = data.get(key);
            Collection<?> items = listObj instanceof Collection<?> c ? c : List.of();
            for (Object item : items) {
                Map<String, Object> itemMap = item instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : Map.of("value", item);
                result.append(interpolate(inner, itemMap));
            }
            cursor = blockEnd + closeTag.length();
        }
        return result.toString();
    }

    private static String interpolate(String template, Map<String, Object> data) {
        if (template == null) return "";
        if (data == null || data.isEmpty()) return template;
        String out = template;
        for (Map.Entry<String, Object> e : data.entrySet()) {
            String token = "{{" + e.getKey() + "}}";
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            out = out.replace(token, value);
        }
        return out;
    }
}
