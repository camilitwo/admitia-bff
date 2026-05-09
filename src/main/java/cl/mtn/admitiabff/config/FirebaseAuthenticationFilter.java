package cl.mtn.admitiabff.config;

import cl.mtn.admitiabff.domain.user.ActiveSessionEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ActiveSessionRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import cl.mtn.admitiabff.service.TokenService;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtro único de autenticación. Acepta dos esquemas en el header {@code Authorization: Bearer ...}:
 * <ol>
 *   <li>Firebase ID Token — validado con la SDK Admin (verifica firma vía JWKS de Google,
 *       project-id, sign_in_provider y email_verified).</li>
 *   <li>JWT propio del BFF (HS256) — validado con {@link JwtService} + blacklist de jti
 *       + sesión activa en BD ({@code active_sessions}).</li>
 * </ol>
 * Todas las validaciones devuelven {@code false} (no autenticado) ante cualquier inconsistencia;
 * el {@code SecurityFilterChain} responde 401 si el endpoint requiere autenticación.
 */
@Component
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthenticationFilter.class);
    /** Sólo actualizar last_activity si han pasado más de N segundos desde la última. */
    private static final long LAST_ACTIVITY_THROTTLE_SEC = 60L;

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ActiveSessionRepository activeSessionRepository;
    private final TokenService tokenService;

    @Value("${app.firebase.allowed-providers:password,google.com,facebook.com,apple.com,microsoft.com}")
    private String allowedProvidersCsv;

    @Value("${app.firebase.max-auth-age-seconds:28800}")
    private long maxAuthAgeSeconds;

    @Value("${app.firebase.check-revoked:true}")
    private boolean checkRevoked;

    public FirebaseAuthenticationFilter(JwtService jwtService,
                                        UserRepository userRepository,
                                        ActiveSessionRepository activeSessionRepository,
                                        TokenService tokenService) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.activeSessionRepository = activeSessionRepository;
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                boolean authenticated = tryFirebaseAuth(token, request);
                if (!authenticated) {
                    authenticated = tryLocalJwtAuth(token, request);
                }
                if (!authenticated) {
                    log.debug("[Auth] Token presente pero no verificable para {}", request.getRequestURI());
                }
            }
        } catch (Exception ex) {
            log.debug("[Auth] Error procesando token: {}", ex.getMessage());
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    // ------------------------------------------------------------------------
    // Firebase ID Token
    // ------------------------------------------------------------------------
    private boolean tryFirebaseAuth(String idToken, HttpServletRequest request) {
        if (FirebaseApp.getApps().isEmpty()) {
            return false;
        }
        try {
            // checkRevoked=true: consulta a Firebase si la sesión fue revocada (logout global)
            FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(idToken, checkRevoked);
            String firebaseUid = decoded.getUid();
            String email = decoded.getEmail();
            boolean emailVerified = decoded.isEmailVerified();

            // Validaciones reforzadas (ver SECURITY_TOKENS.md §5.3)
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> firebaseClaim =
                (java.util.Map<String, Object>) decoded.getClaims().getOrDefault("firebase", java.util.Map.of());
            String provider = String.valueOf(firebaseClaim.getOrDefault("sign_in_provider", ""));
            Number authTime = (Number) decoded.getClaims().get("auth_time");

            Set<String> allowedProviders = Set.of(allowedProvidersCsv.split("\\s*,\\s*"));
            if (!allowedProviders.contains(provider)) {
                log.warn("[Auth/Firebase] Provider no permitido: {} (allowed={})", provider, allowedProviders);
                return false;
            }
            if (authTime != null) {
                long age = (System.currentTimeMillis() / 1000) - authTime.longValue();
                if (age > maxAuthAgeSeconds) {
                    log.warn("[Auth/Firebase] auth_time excedido: age={}s > max={}s", age, maxAuthAgeSeconds);
                    return false;
                }
            }
            // Para providers que no son password/oauth confiable, exigir email_verified.
            if (!"password".equals(provider) && !emailVerified) {
                log.warn("[Auth/Firebase] email_verified=false para provider={}. Rechazado.", provider);
                return false;
            }

            // Buscar SIEMPRE por firebase_uid; sin linking silencioso por email.
            UserEntity user = userRepository.findByFirebaseUid(firebaseUid).orElse(null);
            if (user == null) {
                logUnlinkedAttempt(email, firebaseUid);
                return false;
            }
            if (email != null && user.getEmail() != null && !email.equalsIgnoreCase(user.getEmail())) {
                log.error("[Auth/Firebase] Email mismatch uid={} token={} bd={}. Rechazado.", firebaseUid, email, user.getEmail());
                return false;
            }
            if (!user.isActive()) {
                log.warn("[Auth/Firebase] Usuario inactivo id={} uid={}", user.getId(), firebaseUid);
                return false;
            }
            setAuthentication(user);
            return true;
        } catch (Exception ex) {
            log.warn("[Auth/Firebase] Auth fallida para path={}: {}", request.getRequestURI(), ex.getMessage());
            return false;
        }
    }

    private void logUnlinkedAttempt(String email, String firebaseUid) {
        if (email == null) return;
        UserEntity byEmail = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (byEmail != null && byEmail.getFirebaseUid() == null) {
            log.warn("[Auth/Firebase] email={} existe localmente pero NO está enlazado a firebase_uid={}. "
                + "Use POST /api/auth/firebase-login o /api/auth/firebase/link.", email, firebaseUid);
        } else if (byEmail != null) {
            log.warn("[Auth/Firebase] email={} enlazado a otro firebase_uid (existing={}, incoming={})",
                email, byEmail.getFirebaseUid(), firebaseUid);
        }
    }

    // ------------------------------------------------------------------------
    // JWT propio del BFF (HS256)
    // ------------------------------------------------------------------------
    private boolean tryLocalJwtAuth(String token, HttpServletRequest request) {
        try {
            if (!jwtService.isValid(token)) {
                return false;
            }
            Claims claims = jwtService.extractAllClaims(token);
            String jti = claims.getId();
            String role = claims.get("role", String.class);
            String email = claims.get("email", String.class);
            String sub = claims.getSubject();
            Long userId = null;
            try { userId = Long.parseLong(sub); } catch (NumberFormatException ignored) {}

            // Blacklist de jti (logout o revocación administrativa)
            if (jti != null && tokenService.isJtiRevoked(jti)) {
                log.info("[Auth/Local] jti revocado: {}", jti);
                return false;
            }
            if (role == null || userId == null) {
                return false;
            }

            // Validar sesión activa y refrescar last_activity (con throttle)
            String tokenHash = TokenService.sha256(token);
            ActiveSessionEntity session = activeSessionRepository.findByTokenHash(tokenHash).orElse(null);
            if (session == null) {
                log.info("[Auth/Local] sesión no encontrada para jti={}. Rechazado.", jti);
                return false;
            }
            LocalDateTime now = LocalDateTime.now();
            if (session.getLastActivity() == null
                    || java.time.Duration.between(session.getLastActivity(), now).toSeconds() > LAST_ACTIVITY_THROTTLE_SEC) {
                session.setLastActivity(now);
                activeSessionRepository.save(session);
            }

            var auth = new UsernamePasswordAuthenticationToken(
                email, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
            AuthContext.set(new AuthUser(userId, email, role));
            return true;
        } catch (Exception ex) {
            log.debug("[Auth/Local] JWT no válido: {}", ex.getMessage());
            return false;
        }
    }

    private void setAuthentication(UserEntity user) {
        String role = user.getRole().name();
        var auth = new UsernamePasswordAuthenticationToken(
            user.getEmail(), null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
        AuthContext.set(new AuthUser(user.getId(), user.getEmail(), role));
    }
}
