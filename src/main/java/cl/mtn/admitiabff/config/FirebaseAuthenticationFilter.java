package cl.mtn.admitiabff.config;

import cl.mtn.admitiabff.domain.user.ActiveSessionEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.ActiveSessionRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ActiveSessionRepository activeSessionRepository;

    public FirebaseAuthenticationFilter(JwtService jwtService, UserRepository userRepository, ActiveSessionRepository activeSessionRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.activeSessionRepository = activeSessionRepository;
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
                    authenticated = tryLegacyJwtAuth(token, request);
                }

                if (!authenticated) {
                    log.debug("[Auth] Token present but rejected for {}", request.getRequestURI());
                }
            }
        } catch (Exception ex) {
            log.debug("[Auth] Error processing token: {}", ex.getMessage());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            AuthContext.clear();
        }
    }

    private boolean tryFirebaseAuth(String idToken, HttpServletRequest request) {
        if (FirebaseApp.getApps().isEmpty()) {
            return false;
        }
        try {
            FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(idToken);
            String firebaseUid = decoded.getUid();
            String email = decoded.getEmail();

            UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .or(() -> userRepository.findByEmailIgnoreCase(email))
                .orElse(null);

            if (user == null || !user.isActive() || !isTokenSessionActive(idToken)) {
                return false;
            }

            if (user.getFirebaseUid() == null) {
                try {
                    user.setFirebaseUid(firebaseUid);
                    userRepository.save(user);
                } catch (Exception saveEx) {
                    log.warn("[Auth/Firebase] Could not persist firebase_uid for user id={}: {}", user.getId(), saveEx.getMessage());
                }
            }

            setAuthentication(user);
            touchSession(idToken);
            return true;
        } catch (Exception ex) {
            log.warn("[Auth/Firebase] Auth failed for path={}: {}", request.getRequestURI(), ex.getMessage());
            return false;
        }
    }

    private boolean tryLegacyJwtAuth(String token, HttpServletRequest request) {
        try {
            if (!jwtService.isValid(token) || !isTokenSessionActive(token)) {
                return false;
            }
            var claims = jwtService.extractAllClaims(token);
            String tokenRole = claims.get("role", String.class);
            String email = claims.get("email", String.class);

            if (email == null || email.isBlank() || tokenRole == null || tokenRole.isBlank()) {
                return false;
            }

            UserEntity user = userRepository.findByEmailIgnoreCase(email).orElse(null);
            if (user == null || !user.isActive()) {
                return false;
            }

            String persistedRole = user.getRole().name();
            if (!persistedRole.equals(tokenRole)) {
                log.warn("[Auth/Legacy] Role mismatch blocked for email={} tokenRole={} persistedRole={} path={}",
                    email, tokenRole, persistedRole, request.getRequestURI());
                return false;
            }

            var auth = new UsernamePasswordAuthenticationToken(
                email, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + persistedRole))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
            AuthContext.set(new AuthUser(user.getId(), user.getEmail(), persistedRole));
            touchSession(token);
            return true;
        } catch (Exception ex) {
            log.debug("[Auth/Legacy] Not a valid legacy JWT: {}", ex.getMessage());
            return false;
        }
    }

    private boolean isTokenSessionActive(String token) {
        return activeSessionRepository.findByTokenHash(sha256(token)).isPresent();
    }

    private void touchSession(String token) {
        activeSessionRepository.findByTokenHash(sha256(token)).ifPresent(session -> {
            session.setLastActivity(LocalDateTime.now());
            activeSessionRepository.save(session);
        });
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

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
