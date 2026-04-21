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
        java.util.Map<String, Object> err = new java.util.LinkedHashMap<>();
        err.put("code", code != null ? code : "INTERNAL_ERROR");
        err.put("message", message != null ? message : "Error interno del servidor");
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("success", false);
        resp.put("error", err);
        resp.put("timestamp", Instant.now().toString());
        return resp;
    }
}
