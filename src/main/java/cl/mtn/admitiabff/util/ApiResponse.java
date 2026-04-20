package cl.mtn.admitiabff.util;

import java.time.Instant;
import java.util.Map;

public final class ApiResponse {
    private ApiResponse() {
    }

    public static Map<String, Object> ok(Object data) {
        return Map.of(
            "success", true,
            "data", data,
            "timestamp", Instant.now().toString()
        );
    }

    public static Map<String, Object> ok(String message, Object data) {
        return Map.of(
            "success", true,
            "message", message,
            "data", data,
            "timestamp", Instant.now().toString()
        );
    }

    public static Map<String, Object> error(String code, String message) {
        return Map.of(
            "success", false,
            "error", Map.of("code", code, "message", message),
            "timestamp", Instant.now().toString()
        );
    }
}
