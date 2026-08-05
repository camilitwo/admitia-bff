package cl.mtn.admitiabff.prekinder.service;

public class VersionConflictException extends RuntimeException {
    public VersionConflictException(String message) { super(message); }
}
