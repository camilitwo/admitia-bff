package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.VersionConflictException;
import cl.mtn.admitiabff.prekinder.service.PrekinderDomainException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "cl.mtn.admitiabff.prekinder")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderErrorHandler {
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, Object> badRequest(Exception ignored) { return error("INVALID_REQUEST", "Solicitud inválida"); }

    @ExceptionHandler(VersionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, Object> conflict(Exception ignored) { return error("VERSION_CONFLICT", "El dato cambió; resincroniza antes de continuar"); }

    @ExceptionHandler(PrekinderDomainException.class)
    org.springframework.http.ResponseEntity<Map<String, Object>> domain(PrekinderDomainException exception) {
        return org.springframework.http.ResponseEntity.status(exception.status())
            .body(error(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler({AccessDeniedException.class, SecurityException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    Map<String, Object> forbidden(Exception ignored) { return error("FORBIDDEN", "Acceso denegado"); }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("success", false, "error", Map.of("code", code, "message", message));
    }
}
