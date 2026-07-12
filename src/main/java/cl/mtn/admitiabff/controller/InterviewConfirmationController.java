package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.InterviewConfirmationService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller pasarela para confirmación de entrevistas vía email.
 * <p>
 * Este endpoint es accedido directamente desde los emails (público, no requiere auth).
 * Procesa la confirmación/rechazo y redirige al frontend con el resultado.
 * <p>
 * URL en el email: https://api.mtn.cl/api/public/interview/confirm?token=JWT
 * ↓ (BFF procesa)
 * Redirect 302 → https://portal.mtn.cl/interview/confirmation-result?status=confirmed
 */
@RestController
@RequestMapping("/api/public/interview")
public class InterviewConfirmationController {

    private static final Logger log = LoggerFactory.getLogger(InterviewConfirmationController.class);

    private final InterviewConfirmationService confirmationService;

    public InterviewConfirmationController(InterviewConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    /**
     * Endpoint pasarela para confirmar o rechazar entrevista desde el email.
     * <p>
     * El token JWT contiene:
     * - interviewId: ID de la entrevista
     * - action: CONFIRM o REJECT
     * <p>
     * Flujo:
     * 1. Valida el JWT
     * 2. Actualiza el estado de la entrevista
     * 3. Redirige al frontend con el resultado
     *
     * @param token JWT firmado
     * @return Redirect 302 al frontend con el resultado
     */
    @GetMapping("/confirm")
    public ResponseEntity<Void> confirmInterview(@RequestParam String token, HttpServletRequest request) {
        log.info("[confirm-interview] Recibida petición desde IP: {}", request.getRemoteAddr());

        try {
            // Procesar confirmación y obtener URL de redirección al frontend
            String redirectUrl = confirmationService.processConfirmationAndGetRedirectUrl(token);

            log.info("[confirm-interview] Redirigiendo a frontend: {}", redirectUrl);

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(redirectUrl))
                    .build();

        } catch (IllegalArgumentException e) {
            // Token inválido o expirado - redirigir al frontend con error
            log.warn("[confirm-interview] Token inválido: {}", e.getMessage());
            String errorUrl = confirmationService.buildErrorUrl(e.getMessage());
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(errorUrl))
                    .build();

        } catch (IllegalStateException e) {
            // Entrevista ya no modificable
            log.warn("[confirm-interview] Estado inválido: {}", e.getMessage());
            String errorUrl = confirmationService.buildErrorUrl(e.getMessage());
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(errorUrl))
                    .build();

        } catch (Exception e) {
            log.error("[confirm-interview] Error inesperado procesando confirmación", e);
            String errorUrl = confirmationService.buildErrorUrl("Error procesando la solicitud. Intente nuevamente.");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(errorUrl))
                    .build();
        }
    }
}
