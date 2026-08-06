package cl.mtn.admitiabff.prekinder.security;

import cl.mtn.admitiabff.config.JwtService;
import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.repository.PrekinderActorRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final PrekinderActorRepository actors;
    private final Set<String> bootstrapAdmins;

    public PrekinderAuthenticationFilter(JwtService jwtService, PrekinderActorRepository actors,
        @Value("${app.prekinder.bootstrap-admin-subjects:}") String bootstrapAdmins) {
        this.jwtService = jwtService;
        this.actors = actors;
        this.bootstrapAdmins = bootstrapAdmins == null || bootstrapAdmins.isBlank() ? Set.of()
            : Set.copyOf(Arrays.stream(bootstrapAdmins.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/prekinder/") || path.equals("/api/prekinder/realtime");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(response); return;
        }
        try {
            TokenIdentity identity = resolve(header.substring(7));
            String proposedRole = bootstrapAdmins.contains(identity.subject()) || bootstrapAdmins.contains(identity.email())
                ? "ADMIN" : identity.role();
            PrekinderActor actor = actors.upsertSubject(identity.subject(), sha256(identity.email()), proposedRole);
            var authentication = new UsernamePasswordAuthenticationToken(identity.email(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + actor.role())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            PrekinderAuthContext.set(new PrekinderAuthContext.Principal(actor, identity.subject(), identity.email(), identity.sessionId()));
            try { chain.doFilter(request, response); }
            finally { PrekinderAuthContext.clear(); }
        } catch (Exception exception) {
            writeUnauthorized(response);
        }
    }

    private TokenIdentity resolve(String token) throws Exception {
        if (jwtService.isValid(token)) {
            Claims claims = jwtService.extractAllClaims(token);
            String role = claims.get("role", String.class);
            String email = claims.get("email", String.class);
            return new TokenIdentity("legacy:" + claims.getSubject(), email == null ? "" : email,
                normalizeRole(role), claims.getId());
        }
        if (FirebaseApp.getApps().isEmpty()) throw new SecurityException("Firebase no disponible");
        FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(token, true);
        String email = decoded.getEmail() == null ? "" : decoded.getEmail();
        return new TokenIdentity("firebase:" + decoded.getUid(), email, "APODERADO", "firebase:" + decoded.getUid());
    }

    private static String normalizeRole(String role) {
        if (role == null) return "APODERADO";
        return switch (role) {
            case "ADMIN", "COORDINATOR", "CYCLE_DIRECTOR", "TEACHER", "PSYCHOLOGIST", "INTERVIEWER", "APODERADO" -> role;
            default -> "APODERADO";
        };
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest((value == null ? "" : value.toLowerCase()).getBytes(StandardCharsets.UTF_8)));
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"No autenticado\"}}");
    }

    private record TokenIdentity(String subject, String email, String role, String sessionId) {}
}
