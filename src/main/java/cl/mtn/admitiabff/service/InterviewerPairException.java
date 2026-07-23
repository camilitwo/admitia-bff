package cl.mtn.admitiabff.service;

public class InterviewerPairException extends IllegalArgumentException {
    private final String code;

    public InterviewerPairException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
