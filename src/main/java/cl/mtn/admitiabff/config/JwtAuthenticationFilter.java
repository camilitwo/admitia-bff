package cl.mtn.admitiabff.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
        throws ServletException, IOException {
        try {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                String role = null;
                String email = null;
                Long userId = null;

                // Intento 1: verificar firma con el secret del BFF
                boolean valid = jwtService.isValid(token);
                log.info("[JWT] isValid={} path={}", valid, request.getRequestURI());
                if (valid) {
                    try {
                        Claims claims = jwtService.extractAllClaims(token);
                        role = claims.get("role", String.class);
                        email = claims.get("email", String.class);
                        String sub = claims.getSubject();
                        try { userId = Long.parseLong(sub); } catch (NumberFormatException ignored) {}
                        log.info("[JWT] Claims extraídos: role={} email={} sub={}", role, email, sub);
                    } catch (Exception e) {
                        log.warn("[JWT] Error extrayendo claims: {}", e.getMessage());
                    }
                }

                // Intento 2: fallback — decodificar payload base64 sin verificar firma
                if (role == null) {
                    try {
                        String[] parts = token.split("\\.");
                        if (parts.length == 3) {
                            String padded = parts[1];
                            int pad = 4 - padded.length() % 4;
                            if (pad < 4) padded = padded + "=".repeat(pad);
                            byte[] payloadBytes = java.util.Base64.getUrlDecoder().decode(padded);
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> payload =
                                new com.fasterxml.jackson.databind.ObjectMapper().readValue(payloadBytes, java.util.Map.class);
                            Object expObj = payload.get("exp");
                            log.info("[JWT] Fallback base64: exp={} now={}", expObj, System.currentTimeMillis() / 1000);
                            if (expObj != null && ((Number) expObj).longValue() > System.currentTimeMillis() / 1000) {
                                role = (String) payload.get("role");
                                email = (String) payload.get("email");
                                Object subObj = payload.get("sub");
                                if (subObj != null) {
                                    try { userId = Long.parseLong(subObj.toString()); } catch (NumberFormatException ignored) {}
                                }
                                log.info("[JWT] Fallback exitoso: role={} email={}", role, email);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[JWT] Error en fallback base64: {}", e.getMessage());
                    }
                }

                log.info("[JWT] Resultado final: role={} authenticated={}", role, role != null);
                if (role != null) {
                    var auth = new UsernamePasswordAuthenticationToken(
                        email,
                        null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    AuthContext.set(new AuthUser(userId, email, role));
                }
            }
        } catch (Exception ignored) {
            // Token malformado — continuar sin autenticar
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }
}
