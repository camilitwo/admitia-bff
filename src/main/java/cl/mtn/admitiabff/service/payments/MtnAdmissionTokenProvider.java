package cl.mtn.admitiabff.service.payments;

import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.TokenResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class MtnAdmissionTokenProvider {
    private static final long EXPIRY_SKEW_SECONDS = 60;

    private final RestClient restClient;
    private final MtnAdmissionProperties properties;
    private final Clock clock;
    private volatile CachedToken cachedToken;

    @Autowired
    public MtnAdmissionTokenProvider(RestClient.Builder builder, MtnAdmissionProperties properties) {
        this(builder, properties, Clock.systemUTC());
    }

    MtnAdmissionTokenProvider(RestClient.Builder builder, MtnAdmissionProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.restClient = builder.clone()
            .requestFactory(requestFactory(properties))
            .baseUrl(trimTrailingSlash(properties.baseUrl()))
            .build();
    }

    public String accessToken() {
        properties.validateForUse();
        CachedToken current = cachedToken;
        if (isUsable(current)) return current.value();
        synchronized (this) {
            current = cachedToken;
            if (isUsable(current)) return current.value();
            cachedToken = requestToken();
            return cachedToken.value();
        }
    }

    public synchronized void invalidate() {
        cachedToken = null;
    }

    private CachedToken requestToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        RestClient.RequestBodySpec request = restClient.post()
            .uri(path(properties.tokenPath(), "/auth/token"))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .accept(MediaType.APPLICATION_JSON);
        if (properties.clientAuthMethod() == MtnAdmissionProperties.ClientAuthMethod.FORM) {
            form.add("client_id", properties.clientCode());
            form.add("client_secret", properties.clientSecret());
        } else {
            request.headers(headers -> headers.setBasicAuth(properties.clientCode(), properties.clientSecret()));
        }
        try {
            TokenResponse response = request.body(form).retrieve().body(TokenResponse.class);
            if (response == null || blank(response.accessToken()) || response.expiresIn() <= 0) {
                throw PaymentIntegrationException.auth("La API MTN devolvió un token inválido");
            }
            long usableSeconds = Math.max(1, response.expiresIn() - EXPIRY_SKEW_SECONDS);
            return new CachedToken(response.accessToken(), clock.instant().plusSeconds(usableSeconds));
        } catch (HttpStatusCodeException ex) {
            throw PaymentIntegrationException.auth("La API MTN rechazó las credenciales de integración");
        } catch (ResourceAccessException ex) {
            throw PaymentIntegrationException.unavailable("No fue posible obtener el token de la API MTN");
        }
    }

    private boolean isUsable(CachedToken token) {
        return token != null && clock.instant().isBefore(token.expiresAt());
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://localhost";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String path(String configured, String fallback) {
        String result = blank(configured) ? fallback : configured.trim();
        return result.startsWith("/") ? result : "/" + result;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static SimpleClientHttpRequestFactory requestFactory(MtnAdmissionProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout() == null ? Duration.ofSeconds(3) : properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout() == null ? Duration.ofSeconds(10) : properties.readTimeout());
        return factory;
    }

    private record CachedToken(String value, Instant expiresAt) {}
}
