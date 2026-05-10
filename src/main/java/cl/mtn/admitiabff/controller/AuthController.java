package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Nombre de la cookie HttpOnly que transporta el refresh token. */
    public static final String REFRESH_COOKIE = "admitia_refresh";

    private final AuthService authService;

    @Value("${app.cookies.secure:true}")
    private boolean cookieSecure;

    @Value("${app.cookies.same-site:Strict}")
    private String cookieSameSite;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/csrf-token")
    public Map<String, Object> csrfToken() { return authService.csrfToken(); }

    @GetMapping("/public-key")
    public Map<String, Object> publicKey() { return authService.publicKey(); }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> payload, HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = authService.login(payload, request.getHeader("User-Agent"), request.getRemoteAddr());
        attachRefreshCookie(response, (String) result.get("refreshToken"), longValue(result.get("refreshExpiresIn")));
        return result;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> payload) { return authService.register(payload); }

    @PostMapping("/firebase-login")
    public Map<String, Object> firebaseLogin(@RequestBody Map<String, Object> payload, HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = authService.firebaseLogin(payload);
        attachRefreshCookie(response, (String) result.get("refreshToken"), longValue(result.get("refreshExpiresIn")));
        return result;
    }

    @PostMapping("/firebase-register")
    public Map<String, Object> firebaseRegister(@RequestBody Map<String, Object> payload, HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = authService.firebaseRegister(payload);
        attachRefreshCookie(response, (String) result.get("refreshToken"), longValue(result.get("refreshExpiresIn")));
        return result;
    }

    /**
     * Envía el correo de verificación de email DESDE NUESTRA CASILLA (no desde el dominio
     * por defecto de Firebase). Se llama después de {@code /firebase-register} para no
     * bloquear la creación de la cuenta cuando el email aún no está verificado.
     */
    @PostMapping("/firebase/send-verification-email")
    public Map<String, Object> sendFirebaseVerificationEmail(@RequestBody(required = false) Map<String, Object> payload) {
        return authService.sendFirebaseVerificationLink(payload == null ? Map.of() : payload);
    }

    /**
     * Enlaza la cuenta del usuario autenticado con su identidad de Firebase.
     */
    @PostMapping("/firebase/link")
    public Map<String, Object> linkFirebase(@RequestBody Map<String, Object> payload) {
        return authService.linkFirebase(payload);
    }

    /**
     * Endpoint público (sin Bearer) que recibe el clic del correo de verificación.
     * Recibe los mismos query params que Firebase ({@code mode}, {@code oobCode},
     * {@code apiKey}, {@code lang}, {@code continueUrl}) y emite una redirección 302
     * a la URL real de Firebase. Esto permite camuflar {@code firebaseapp.com}
     * detrás de nuestro dominio en el cuerpo del correo.
     */
    @GetMapping("/firebase/verify-redirect")
    public ResponseEntity<Void> firebaseVerifyRedirect(
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "oobCode", required = false) String oobCode,
            @RequestParam(value = "apiKey", required = false) String apiKey,
            @RequestParam(value = "lang", required = false) String lang,
            @RequestParam(value = "continueUrl", required = false) String continueUrl) {
        Map<String, String> params = new LinkedHashMap<>();
        if (mode != null) params.put("mode", mode);
        if (oobCode != null) params.put("oobCode", oobCode);
        if (apiKey != null) params.put("apiKey", apiKey);
        if (lang != null) params.put("lang", lang);
        if (continueUrl != null) params.put("continueUrl", continueUrl);
        String target = authService.resolveFirebaseVerificationTarget(params);
        return ResponseEntity.status(302).location(URI.create(target)).build();
    }

    /**
     * Rota el refresh token y devuelve un access token nuevo. Lee el refresh desde la cookie
     * HttpOnly o, en transición, desde el body ({@code refreshToken}).
     */
    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody(required = false) Map<String, Object> payload,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        String refresh = readCookie(request, REFRESH_COOKIE);
        if (refresh == null && payload != null) {
            Object inBody = payload.get("refreshToken");
            if (inBody instanceof String s && !s.isBlank()) refresh = s;
        }
        Map<String, Object> result = authService.refresh(refresh, request.getHeader("User-Agent"), request.getRemoteAddr());
        attachRefreshCookie(response, (String) result.get("refreshToken"), longValue(result.get("refreshExpiresIn")));
        return result;
    }

    /**
     * Cierra la sesión: revoca el refresh token y agrega el jti del access a la blacklist.
     * Idempotente — siempre devuelve 200.
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestBody(required = false) Map<String, Object> payload,
                                      HttpServletRequest request,
                                      HttpServletResponse response) {
        String refresh = readCookie(request, REFRESH_COOKIE);
        if (refresh == null && payload != null) {
            Object inBody = payload.get("refreshToken");
            if (inBody instanceof String s && !s.isBlank()) refresh = s;
        }
        String accessToken = null;
        String authz = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authz != null && authz.startsWith("Bearer ")) {
            accessToken = authz.substring(7);
        }
        Map<String, Object> result = authService.logout(accessToken, refresh);
        clearRefreshCookie(response);
        return result;
    }

    @PostMapping("/check-email")
    public Map<String, Object> checkEmail(@RequestBody Map<String, Object> payload) { return authService.checkEmail(payload); }

    @GetMapping("/check-email")
    public boolean checkEmailGet(@org.springframework.web.bind.annotation.RequestParam String email) {
        Map<String, Object> result = authService.checkEmail(java.util.Map.of("email", email));
        return Boolean.TRUE.equals(result.get("exists"));
    }

    @GetMapping("/check")
    public Map<String, Object> check() { return authService.check(); }

    @PutMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody Map<String, Object> payload) { return authService.changePassword(payload); }

    // ---- helpers de cookie ------------------------------------------------
    private void attachRefreshCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
        if (token == null) return;
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, token)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(cookieSameSite)
            .path("/api/auth")
            .maxAge(Duration.ofSeconds(maxAgeSeconds > 0 ? maxAgeSeconds : 0))
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, "")
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(cookieSameSite)
            .path("/api/auth")
            .maxAge(Duration.ZERO)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) return c.getValue();
        }
        return null;
    }

    private static long longValue(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return 0L; }
    }
}
