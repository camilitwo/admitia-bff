package cl.mtn.admitiabff.config;

public record AuthUser(Long id, String email, String role, String sessionId) {
    public AuthUser(Long id, String email, String role) {
        this(id, email, role, "legacy-test-session");
    }
}
