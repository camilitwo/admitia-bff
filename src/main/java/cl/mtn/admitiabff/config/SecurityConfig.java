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
import org.springframework.beans.factory.ObjectProvider;
import cl.mtn.admitiabff.prekinder.security.PrekinderAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private final FirebaseAuthenticationFilter firebaseAuthenticationFilter;
    private final TemporaryPasswordEnforcementFilter temporaryPasswordEnforcementFilter;
    private final PrekinderAuthenticationFilter prekinderAuthenticationFilter;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${app.prekinder.realtime.allowed-origins:http://localhost:5173}")
    private String prekinderAllowedOrigins;

    public SecurityConfig(FirebaseAuthenticationFilter firebaseAuthenticationFilter,
                          TemporaryPasswordEnforcementFilter temporaryPasswordEnforcementFilter,
                          ObjectProvider<PrekinderAuthenticationFilter> prekinderAuthenticationFilter) {
        this.firebaseAuthenticationFilter = firebaseAuthenticationFilter;
        this.temporaryPasswordEnforcementFilter = temporaryPasswordEnforcementFilter;
        this.prekinderAuthenticationFilter = prekinderAuthenticationFilter.getIfAvailable();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        HttpSecurity configured = http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) ->
                    writeJsonError(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED", "No autenticado"))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                    writeJsonError(request, response, HttpServletResponse.SC_FORBIDDEN,
                        "INSUFFICIENT_PERMISSION", "Acceso denegado"))
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/ready", "/gateway/status", "/v3/api-docs/**").permitAll()
                // El upgrade no lleva Bearer: se autentica una vez con ticket en el frame STOMP CONNECT.
                .requestMatchers("/api/prekinder/realtime").permitAll()
                // Alta autocontenida: sólo permite registrarse a emails previamente creados
                // como profesionales activos dentro del módulo aislado de Prekínder.
                .requestMatchers(HttpMethod.POST, "/api/prekinder/professional-registration").permitAll()
                // Cada recurso Prekínder aplica autorización por actor/asignación dentro del
                // datasource aislado. Aquí sólo exigimos una identidad autenticada.
                .requestMatchers("/api/prekinder/**").authenticated()
                // Endpoints de auth abiertos (login/logout/refresh deben ser accesibles sin Bearer válido)
                .requestMatchers("/api/auth/**", "/api/email/**", "/api/institutional-emails/**", "/api/public/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/users/roles", "/api/users/public/**", "/api/applications/stats", "/api/applications/statistics",
                    "/api/applications/public/**", "/api/applications/*/contact", "/api/applications/debug/system-info",
                    "/api/interviews/public/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(firebaseAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(temporaryPasswordEnforcementFilter, FirebaseAuthenticationFilter.class)
            ;
        if (prekinderAuthenticationFilter != null) {
            configured.addFilterBefore(prekinderAuthenticationFilter, FirebaseAuthenticationFilter.class);
        }
        return configured.build();
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
        CorsConfiguration prekinder = new CorsConfiguration();
        prekinder.setAllowedOrigins(Arrays.stream(prekinderAllowedOrigins.split(",")).map(String::trim).toList());
        prekinder.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        prekinder.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin", "X-Request-ID"));
        prekinder.setAllowCredentials(true);
        prekinder.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/prekinder/**", prekinder);
        return source;
    }

    private static void writeJsonError(jakarta.servlet.http.HttpServletRequest request,
                                       HttpServletResponse response, int status, String code, String message) throws IOException {
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null || requestId.isBlank()) requestId = java.util.UUID.randomUUID().toString();
        String body = String.format(
            "{\"success\":false,\"error\":{\"code\":\"%s\",\"message\":\"%s\",\"requestId\":\"%s\",\"details\":{}}}",
            code,
            message.replace("\"", "\\\""),
            requestId.replace("\"", "")
        );
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(body);
    }
}
