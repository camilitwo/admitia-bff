package cl.mtn.admitiabff.prekinder.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderSurfaceGuardFilter extends OncePerRequestFilter {
    private final boolean apiEnabled;
    private final boolean publicEnabled;
    private final boolean internalEnabled;

    public PrekinderSurfaceGuardFilter(
        @Value("${app.prekinder.surfaces.api-enabled:true}") boolean apiEnabled,
        @Value("${app.prekinder.surfaces.public-enabled:false}") boolean publicEnabled,
        @Value("${app.prekinder.surfaces.internal-enabled:false}") boolean internalEnabled
    ) {
        this.apiEnabled = apiEnabled;
        this.publicEnabled = publicEnabled;
        this.internalEnabled = internalEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/prekinder/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean publicRequest = isPublicRequest(request.getMethod(), path);
        boolean realtimeRequest = path.startsWith("/api/prekinder/realtime");
        boolean surfaceEnabled = realtimeRequest ? publicEnabled || internalEnabled
            : publicRequest ? publicEnabled : internalEnabled;
        if (apiEnabled && surfaceEnabled) {
            filterChain.doFilter(request, response);
            return;
        }
        byte[] body = ("{\"success\":false,\"error\":{\"code\":\"PREKINDER_SURFACE_DISABLED\","
            + "\"message\":\"La superficie Prekínder solicitada está deshabilitada\"}}")
            .getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static boolean isPublicRequest(String method, String path) {
        if (path.equals("/api/prekinder/application-options")) return true;
        if (path.matches("/api/prekinder/processes/[^/]+/application-draft(?:/.*)?")) return true;
        if (path.equals("/api/prekinder/applications") && "POST".equals(method)) return true;
        if (path.equals("/api/prekinder/me/applications") || path.equals("/api/prekinder/me/offers")) return true;
        if (path.matches("/api/prekinder/offers/[^/]+/response")) return true;
        if (path.matches("/api/prekinder/applications/[^/]+/(?:payments|incorporation-payments)(?:/.*)?")) return true;
        if (path.matches("/api/prekinder/applications/[^/]+/complementary-form(?:/.*)?")) return true;
        if (path.matches("/api/prekinder/applications/[^/]+/inclusion")
            && ("GET".equals(method) || "POST".equals(method))) return true;
        if (path.matches("/api/prekinder/applications/[^/]+/documents")
            && ("GET".equals(method) || "POST".equals(method))) return true;
        return path.matches("/api/prekinder/documents/[^/]+") && "GET".equals(method);
    }
}
