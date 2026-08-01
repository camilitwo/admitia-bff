package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.email.EmailRequestDTO;
import cl.mtn.admitiabff.domain.interview.InterviewEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.service.notification.EmailComposerService;
import cl.mtn.admitiabff.util.EmailDisplayFormatter;
import cl.mtn.admitiabff.util.TemplateUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Servicio de confirmación de entrevistas mediante patrón pasarela.
 * <p>
 * El flujo es:
 * 1. Se genera un JWT con interviewId y action (CONFIRM/REJECT)
 * 2. El email contiene URL al BFF: /api/public/interview/confirm?token=JWT
 * 3. El padre hace clic → llega al BFF
 * 4. BFF valida JWT, actualiza entrevista
 * 5. BFF redirige al frontend con el resultado
 */
@Service
public class InterviewConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(InterviewConfirmationService.class);

    private final InterviewRepository interviewRepository;
    private final EmailComposerService emailComposerService;
    private final SecretKey signingKey;
    private final String frontendBaseUrl;
    private final String resultPath;
    private final long tokenExpiryHours;
    private final String admissionsEmail;

    public InterviewConfirmationService(
            InterviewRepository interviewRepository,
            EmailComposerService emailComposerService,
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.frontend.base-url:http://localhost:5173}") String frontendBaseUrl,
            @Value("${app.confirmation.result-path:/interview/confirmation-result}") String resultPath,
            @Value("${app.confirmation.token-expiry-hours:168}") long tokenExpiryHours,
            @Value("${app.admissions.email:camilo.igv@gmail.com}") String admissionsEmail) {
        this.interviewRepository = interviewRepository;
        this.emailComposerService = emailComposerService;
        this.signingKey = initSigningKey(jwtSecret);
        this.frontendBaseUrl = frontendBaseUrl;
        this.resultPath = resultPath;
        this.tokenExpiryHours = tokenExpiryHours;
        this.admissionsEmail = admissionsEmail;
    }

    private SecretKey initSigningKey(String secret) {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secret);
        } catch (IllegalArgumentException ex) {
            keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                "APP_JWT_SECRET inseguro: requiere al menos 32 bytes (256 bits).");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Genera URL de confirmación completa (para incluir en el email).
     * La URL apunta al BFF (pasarela), no directamente al frontend.
     *
     * @param baseUrl URL base del BFF (ej: https://api.mtn.cl o se obtiene del request)
     * @param interviewId ID de la entrevista
     * @param confirm true para confirmar, false para rechazar
     * @return URL completa del BFF con token JWT
     */
    public String generateConfirmationUrl(String baseUrl, Long interviewId, boolean confirm) {
        String token = generateToken(interviewId, confirm ? "CONFIRM" : "REJECT");

        // Construir URL completa apuntando al BFF
        return UriComponentsBuilder.fromUriString(baseUrl)
                .path("/api/public/interview/confirm")
                .queryParam("token", token)
                .toUriString();
    }

    /**
     * Procesa la confirmación desde el token JWT y retorna la URL de redirección al frontend.
     *
     * @param token JWT recibido
     * @return URL del frontend con el resultado
     */
    @Transactional
    public String processConfirmationAndGetRedirectUrl(String token) {
        // 1. Validar y decodificar token
        ConfirmationClaims claims = parseToken(token);
        Long interviewId = claims.interviewId();
        String action = claims.action();

        log.info("[process-confirmation] Procesando {} para interviewId={}", action, interviewId);

        // 2. Buscar entrevista
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new IllegalArgumentException("Entrevista no encontrada: " + interviewId));

        // 3. Validar que no esté ya procesada (completada, cancelada, confirmada o rechazada)
        if (interview.getStatus() == InterviewStatus.COMPLETED ||
            interview.getStatus() == InterviewStatus.CANCELLED) {
            throw new IllegalStateException("La entrevista ya no puede ser modificada");
        }
        if (interview.getStatus() == InterviewStatus.CONFIRMED) {
            throw new IllegalStateException("La entrevista ya fue confirmada anteriormente");
        }
        if (interview.getStatus() == InterviewStatus.REJECTED_BY_FAMILY) {
            throw new IllegalStateException("La entrevista ya fue rechazada anteriormente");
        }

        // 4. Procesar según acción
        String status;
        String message;

        if ("CONFIRM".equals(action)) {
            interview.setStatus(InterviewStatus.CONFIRMED);
            interview.setConfirmationStatus(InterviewStatus.CONFIRMED);  // Marcamos respuesta del apoderado
            status = "confirmed";
            message = "Entrevista confirmada exitosamente";
            log.info("[process-confirmation] Entrevista {} confirmada", interviewId);
            
            // Notificar a admisiones
            notifyAdmissionsOfConfirmation(interview, true);
            // Notificar a los entrevistadores asignados
            notifyInterviewersOfConfirmation(interview);
        } else if ("REJECT".equals(action)) {
            interview.setStatus(InterviewStatus.REJECTED_BY_FAMILY);
            interview.setConfirmationStatus(InterviewStatus.REJECTED_BY_FAMILY);  // Marcamos respuesta del apoderado
            status = "rejected";
            message = "Ha indicado que no puede asistir. El coordinador le contactará para reprogramar.";
            log.info("[process-confirmation] Entrevista {} rechazada por familia", interviewId);

            // Notificar a admisiones y liberar slot
            notifyCoordinatorOfRejection(interview);
        } else {
            throw new IllegalArgumentException("Acción no válida: " + action);
        }

        interviewRepository.save(interview);

        // 5. Construir URL de redirección al frontend
        return buildResultUrl(status, interviewId, message);
    }

    /**
     * Construye URL de error para redirección al frontend.
     * El frontend (main.tsx) se encarga de redirigir al HashRouter.
     * Ejemplo: https://domain.com/interview/confirmation-result?status=error&message=...
     */
    public String buildErrorUrl(String errorMessage) {
        String encodedMessage = URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);
        return frontendBaseUrl + resultPath + "?status=error&message=" + encodedMessage;
    }

    /**
     * Construye URL de resultado exitoso.
     * El frontend (main.tsx) redirige automáticamente al HashRouter.
     * Ejemplo: https://domain.com/interview/confirmation-result?status=confirmed&interviewId=123&message=...
     */
    private String buildResultUrl(String status, Long interviewId, String message) {
        String encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8);
        return frontendBaseUrl + resultPath
                + "?status=" + status
                + "&interviewId=" + interviewId
                + "&message=" + encodedMessage;
    }

    /**
     * Genera JWT con interviewId y action.
     */
    private String generateToken(Long interviewId, String action) {
        Instant now = Instant.now();
        Instant expiry = now.plus(tokenExpiryHours, ChronoUnit.HOURS);

        return Jwts.builder()
                .subject(String.valueOf(interviewId))
                .claim("interviewId", interviewId)
                .claim("action", action)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Parsea y valida el JWT.
     */
    private ConfirmationClaims parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long interviewId = claims.get("interviewId", Long.class);
            String action = claims.get("action", String.class);

            if (interviewId == null || action == null) {
                throw new IllegalArgumentException("Token inválido: faltan claims");
            }

            return new ConfirmationClaims(interviewId, action);
        } catch (Exception e) {
            log.warn("[parse-token] Error validando token: {}", e.getMessage());
            throw new IllegalArgumentException("Token inválido o expirado");
        }
    }

    private void notifyCoordinatorOfRejection(InterviewEntity interview) {
        // Notificar a admisiones del rechazo
        notifyAdmissionsOfConfirmation(interview, false);
        
        // Liberar el slot (la entrevista queda marcada como REJECTED_BY_FAMILY
        // y el slot ya no bloquea el horario porque findBlockingForInterviewer excluye este estado)
        log.info("[notify-coordinator] Entrevista {} rechazada - slot liberado automáticamente", interview.getId());
    }
    
    /**
     * Notifica a admisiones (coordinador) sobre la confirmación o rechazo de la entrevista.
     */
    private void notifyAdmissionsOfConfirmation(InterviewEntity interview, boolean confirmed) {
        try {
            var application = interview.getApplication();
            String studentName = application.getStudent() != null 
                    ? application.getStudent().getFirstName() + " " + application.getStudent().getPaternalLastName()
                    : "Estudiante";
            String applicantEmail = application.getApplicantUser() != null 
                    ? application.getApplicantUser().getEmail() 
                    : "Sin email";
            
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("interviewId", interview.getId());
            data.put("studentName", studentName);
            data.put("applicantEmail", applicantEmail);
            data.put("scheduledDate", interview.getScheduledDate().toString());
            data.put("scheduledTime", interview.getScheduledTime().toString());
            data.put("mode", interview.getMode());
            data.put("location", interview.getLocation());
            data.put("action", confirmed ? "CONFIRMED" : "REJECTED");
            data.put("statusText", confirmed ? "Confirmada por la familia" : "Rechazada por la familia");
            
            String subject = confirmed 
                    ? "Entrevista CONFIRMADA - " + studentName
                    : "Entrevista RECHAZADA - " + studentName;
            
            // Renderizar el template HTML antes de enviar
            String templateName = confirmed ? "interview_confirmed" : "interview_rejected_by_family";
            String bodyHtml = TemplateUtils.generateTemplate(templateName, data);
            
            emailComposerService.send(EmailRequestDTO.builder()
                    .template(bodyHtml)
                    .to(admissionsEmail)
                    .subject(subject)
                    .recipientType("SYSTEM")
                    .data(data)
                    .build());
            
            log.info("[notify-admissions] Email enviado a {} para entrevista {} - {}", 
                    admissionsEmail, interview.getId(), confirmed ? "confirmada" : "rechazada");
        } catch (Exception e) {
            log.error("[notify-admissions] Error enviando email a admisiones para entrevista {}: {}", 
                    interview.getId(), e.getMessage(), e);
        }
    }

    /**
     * Notifica a los entrevistadores asignados que la familia confirmó la entrevista.
     * Se invoca solo al confirmar (no al rechazar).
     */
    private void notifyInterviewersOfConfirmation(InterviewEntity interview) {
        var application = interview.getApplication();
        String studentName = application.getStudent() != null
                ? application.getStudent().getFirstName() + " " + application.getStudent().getPaternalLastName()
                : "Estudiante";

        String subject = "Entrevista confirmada - " + studentName + " - " + interview.getScheduledDate();

        // Notificar al entrevistador principal
        if (interview.getInterviewer() != null && interview.getInterviewer().getEmail() != null) {
            sendInterviewerNotification(interview, interview.getInterviewer(), studentName, subject);
        }

        // Notificar al segundo entrevistador (si existe)
        if (interview.getSecondInterviewer() != null && interview.getSecondInterviewer().getEmail() != null) {
            sendInterviewerNotification(interview, interview.getSecondInterviewer(), studentName, subject);
        }
    }

    private void sendInterviewerNotification(InterviewEntity interview,
                                              UserEntity interviewer,
                                              String studentName, String subject) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("interviewerName", interviewer.getFirstName() + " " + interviewer.getLastName());
            data.put("studentName", studentName);
            data.put("interviewType", EmailDisplayFormatter.interviewType(interview.getInterviewType()));
            data.put("scheduledDate", interview.getScheduledDate().toString());
            data.put("scheduledTime", interview.getScheduledTime().toString());
            data.put("mode", EmailDisplayFormatter.mode(interview.getMode()));
            data.put("location", interview.getLocation());

            String bodyHtml = TemplateUtils.generateTemplate("interview_confirmed_for_interviewer", data);

            emailComposerService.send(EmailRequestDTO.builder()
                    .template(bodyHtml)
                    .to(interviewer.getEmail())
                    .subject(subject)
                    .recipientType("INTERVIEWER")
                    .data(data)
                    .build());

            log.info("[notify-interviewers] Email enviado a {} para entrevista {}",
                    interviewer.getEmail(), interview.getId());
        } catch (Exception e) {
            log.error("[notify-interviewers] Error enviando email a {}: {}",
                    interviewer.getEmail(), e.getMessage(), e);
        }
    }

    private record ConfirmationClaims(Long interviewId, String action) {}
}
