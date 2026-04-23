package cl.mtn.admitiabff.config;

import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.UserRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentication filter that supports both Firebase idTokens and legacy JWT tokens.
 * <p>
 * Order of verification:
 * 1. Try Firebase Auth (verifyIdToken) — preferred
 * 2. Fallback to legacy JwtService — for backward compatibility during transition
 */
@Component
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public FirebaseAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
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
                    log.debug("[Auth] Token present but could not be verified for {}", request.getRequestURI());
                }
            }
        } catch (Exception ex) {
            log.debug("[Auth] Error processing token: {}", ex.getMessage());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
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

            log.info("[Auth/Firebase] Verified token: uid={} email={} path={}", firebaseUid, email, request.getRequestURI());

            // Look up user by firebase_uid first, then by email as fallback
            UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .or(() -> userRepository.findByEmailIgnoreCase(email))
                .orElse(null);

            if (user == null) {
                log.warn("[Auth/Firebase] No local user found for uid={} email={}", firebaseUid, email);
                return false;
            }

            // Link firebase_uid if not already set
            if (user.getFirebaseUid() == null) {
                user.setFirebaseUid(firebaseUid);
                userRepository.save(user);
                log.info("[Auth/Firebase] Linked firebase_uid={} to user id={}", firebaseUid, user.getId());
            }

            setAuthentication(user);
            return true;
        } catch (Exception ex) {
            log.debug("[Auth/Firebase] Not a valid Firebase token: {}", ex.getMessage());
            return false;
        }
    }

    private boolean tryLegacyJwtAuth(String token, HttpServletRequest request) {
        try {
            if (!jwtService.isValid(token)) {
                return false;
            }
            var claims = jwtService.extractAllClaims(token);
            String role = claims.get("role", String.class);
            String email = claims.get("email", String.class);
            String sub = claims.getSubject();
            Long userId = null;
            try {
                userId = Long.parseLong(sub);
            } catch (NumberFormatException ignored) {
            }

            log.info("[Auth/Legacy] Verified token: role={} email={} sub={} path={}", role, email, sub, request.getRequestURI());

            if (role != null) {
                var auth = new UsernamePasswordAuthenticationToken(
                    email, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
                AuthContext.set(new AuthUser(userId, email, role));
                return true;
            }
            return false;
        } catch (Exception ex) {
            log.debug("[Auth/Legacy] Not a valid legacy JWT: {}", ex.getMessage());
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
