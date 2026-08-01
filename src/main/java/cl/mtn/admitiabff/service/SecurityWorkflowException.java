package cl.mtn.admitiabff.service;

import org.springframework.http.HttpStatus;

public class SecurityWorkflowException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public SecurityWorkflowException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
