package cl.mtn.admitiabff.service;

import java.util.List;
import java.util.Map;

public class ManualInterviewConfirmationException extends RuntimeException {
    private final List<Map<String, Object>> warnings;

    public ManualInterviewConfirmationException(List<Map<String, Object>> warnings) {
        super("Revisa y confirma las advertencias antes de guardar la entrevista excepcional");
        this.warnings = List.copyOf(warnings);
    }

    public List<Map<String, Object>> getWarnings() {
        return warnings;
    }
}
