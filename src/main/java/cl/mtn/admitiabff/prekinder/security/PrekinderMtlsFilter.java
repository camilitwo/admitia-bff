package cl.mtn.admitiabff.prekinder.security;

import cl.mtn.admitiabff.prekinder.config.PrekinderProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderMtlsFilter extends OncePerRequestFilter {
    private final PrekinderProperties properties;

    public PrekinderMtlsFilter(PrekinderProperties properties) { this.properties = properties; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/prekinder/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (properties.mtls().enforced()) {
            Object certificates = request.getAttribute("jakarta.servlet.request.X509Certificate");
            boolean correctConnector = request.isSecure() && request.getLocalPort() == properties.mtls().port();
            if (!correctConnector || !(certificates instanceof java.security.cert.X509Certificate[] chainValue)
                    || chainValue.length == 0) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
