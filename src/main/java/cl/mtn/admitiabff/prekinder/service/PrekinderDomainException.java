package cl.mtn.admitiabff.prekinder.service;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class PrekinderDomainException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public PrekinderDomainException(String code, String message, HttpStatus status) {
        this(code, message, status, Map.of());
    }

    public PrekinderDomainException(String code, String message, HttpStatus status, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details == null ? Map.of() : details;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
    public Map<String, Object> details() { return details; }

    public static PrekinderDomainException conflict(String code, String message) {
        return new PrekinderDomainException(code, message, HttpStatus.CONFLICT);
    }

    public static PrekinderDomainException conflict(String code, String message, Map<String, Object> details) {
        return new PrekinderDomainException(code, message, HttpStatus.CONFLICT, details);
    }

    public static PrekinderDomainException forbidden(String code, String message) {
        return new PrekinderDomainException(code, message, HttpStatus.FORBIDDEN);
    }
}
