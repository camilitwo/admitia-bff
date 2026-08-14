package cl.mtn.admitiabff.prekinder.service;

import org.springframework.http.HttpStatus;

public class PrekinderDomainException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public PrekinderDomainException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }

    public static PrekinderDomainException conflict(String code, String message) {
        return new PrekinderDomainException(code, message, HttpStatus.CONFLICT);
    }

    public static PrekinderDomainException forbidden(String code, String message) {
        return new PrekinderDomainException(code, message, HttpStatus.FORBIDDEN);
    }
}
