package cl.mtn.admitiabff.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private final FirebaseAuthenticationFilter firebaseAuthenticationFilter;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    public SecurityConfig(FirebaseAuthenticationFilter firebaseAuthenticationFilter) {
        this.firebaseAuthenticationFilter = firebaseAuthenticationFilter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) ->
                    writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "No autenticado"))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                    writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Acceso denegado"))
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/ready", "/gateway/status").permitAll()
                // Endpoints de auth abiertos (login/logout/refresh deben ser accesibles sin Bearer válido)
                .requestMatchers("/api/auth/**", "/api/email/**", "/api/institutional-emails/**", "/api/public/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/users/roles", "/api/users/public/**", "/api/applications/stats", "/api/applications/statistics",
                    "/api/applications/public/**", "/api/applications/*/contact", "/api/applications/debug/system-info",
                    "/api/interviews/public/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(firebaseAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Headers explícitos en lugar de "*" — exigido por allowCredentials=true en muchos navegadores.
        configuration.setAllowedHeaders(List.of(
            "Authorization", "Content-Type", "Accept", "X-Requested-With",
            "X-CSRF-Token", "X-Forwarded-For", "User-Agent", "X-Base-Url"
        ));
        configuration.setExposedHeaders(List.of("Authorization", "Set-Cookie"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static void writeJsonError(HttpServletResponse response, int status, String code, String message) throws IOException {
        String body = String.format(
            "{\"success\":false,\"error\":{\"code\":\"%s\",\"message\":\"%s\"}}",
            code,
            message.replace("\"", "\\\"")
        );
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(body);
    }
}
