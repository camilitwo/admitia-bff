package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.VersionConflictException;
import cl.mtn.admitiabff.prekinder.service.PrekinderDomainException;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "cl.mtn.admitiabff.prekinder")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderErrorHandler {
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, Object> badRequest(Exception ignored, HttpServletRequest request) {
        return error("VALIDATION_ERROR", "Solicitud inválida", request);
    }

    @ExceptionHandler(VersionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, Object> conflict(Exception ignored, HttpServletRequest request) {
        return error("VERSION_CONFLICT", "El dato cambió; resincroniza antes de continuar", request);
    }

    @ExceptionHandler(PrekinderDomainException.class)
    org.springframework.http.ResponseEntity<Map<String, Object>> domain(PrekinderDomainException exception,
                                                                        HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.status(exception.status())
            .body(error(exception.code(), exception.getMessage(), request));
    }

    @ExceptionHandler({AccessDeniedException.class, SecurityException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    Map<String, Object> forbidden(Exception ignored, HttpServletRequest request) {
        return error("INSUFFICIENT_PERMISSION", "Acceso denegado", request);
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, Object> notFound(Exception ignored, HttpServletRequest request) {
        return error("RESOURCE_NOT_FOUND", "El recurso solicitado no existe", request);
    }

    private static Map<String, Object> error(String code, String message, HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null || requestId.isBlank()) requestId = java.util.UUID.randomUUID().toString();
        return Map.of("success", false, "error", Map.of(
            "code", code, "message", message, "requestId", requestId, "details", Map.of()));
    }
}
