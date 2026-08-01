package cl.mtn.admitiabff.config;

import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TemporaryPasswordEnforcementFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED_PATHS = Set.of(
        "/api/auth/change-temporary-password",
        "/api/auth/logout",
        "/api/auth/refresh",
        "/api/auth/check"
    );
    private final UserRepository userRepository;

    public TemporaryPasswordEnforcementFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = AuthContext.get();
        if (auth == null || auth.id() == null || ALLOWED_PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        UserEntity user = userRepository.findById(auth.id()).orElse(null);
        if (user == null || !user.isMustChangePassword()) {
            chain.doFilter(request, response);
            return;
        }
        boolean expired = user.getTemporaryPasswordExpiresAt() == null
            || !user.getTemporaryPasswordExpiresAt().isAfter(LocalDateTime.now());
        writeError(response,
            expired ? "TEMPORARY_PASSWORD_EXPIRED" : "PASSWORD_CHANGE_REQUIRED",
            expired ? "La contraseña temporal venció. Solicite una nueva al administrador"
                    : "Debe definir una nueva contraseña antes de continuar");
    }

    private void writeError(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"" + code
            + "\",\"message\":\"" + message + "\"}}");
    }
}
