package cl.mtn.admitiabff.service.payments;

import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.AdmissionRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.AdmissionResponse;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeResponse;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeStatusResponse;
import java.util.function.Function;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class MtnAdmissionClient implements MtnAdmissionGateway {
    private static final Logger log = LoggerFactory.getLogger(MtnAdmissionClient.class);

    private final RestClient restClient;
    private final MtnAdmissionProperties properties;
    private final MtnAdmissionTokenProvider tokenProvider;

    public MtnAdmissionClient(RestClient.Builder builder, MtnAdmissionProperties properties, MtnAdmissionTokenProvider tokenProvider) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.restClient = builder.clone()
            .requestFactory(requestFactory(properties))
            .baseUrl(trimTrailingSlash(properties.baseUrl()))
            .build();
    }

    @Override
    public AdmissionResponse synchronizeAdmission(AdmissionRequest body) {
        return traced("admission.sync", "POST", properties.guardiansPath(), () -> withBearer("admission.sync", token -> restClient.post()
            .uri(path(properties.guardiansPath(), "/admision/apoderados"))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .headers(headers -> headers.setBearerAuth(token))
            .body(body)
            .retrieve()
            .body(AdmissionResponse.class)));
    }

    @Override
    public ChargeResponse createCharge(ChargeRequest body) {
        return traced("charge.create", "POST", properties.chargesPath(), () -> withBearer("charge.create", token -> restClient.post()
            .uri(path(properties.chargesPath(), "/admision/cobros"))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .headers(headers -> headers.setBearerAuth(token))
            .body(body)
            .retrieve()
            .body(ChargeResponse.class)));
    }

    @Override
    public ChargeStatusResponse chargeStatus(Long chargeId) {
        return traced("charge.status", "GET", properties.chargesPath(), () -> withBearer("charge.status", token -> restClient.get()
            .uri(path(properties.chargesPath(), "/admision/cobros") + "/{chargeId}", chargeId)
            .accept(MediaType.APPLICATION_JSON)
            .headers(headers -> headers.setBearerAuth(token))
            .retrieve()
            .body(ChargeStatusResponse.class)));
    }

    private <T> T withBearer(String operationName, Function<String, T> operation) {
        properties.validateForUse();
        try {
            return operation.apply(tokenProvider.accessToken());
        } catch (HttpClientErrorException.Unauthorized ex) {
            log.warn("[mtn-api] operation={} receivedHttp401=true retryingWithNewToken=true", operationName);
            tokenProvider.invalidate();
            try {
                return operation.apply(tokenProvider.accessToken());
            } catch (RuntimeException retryFailure) {
                throw map(operationName, retryFailure);
            }
        } catch (RuntimeException ex) {
            throw map(operationName, ex);
        }
    }

    private RuntimeException map(String operationName, RuntimeException ex) {
        if (ex instanceof PaymentIntegrationException) return ex;
        if (ex instanceof HttpStatusCodeException http) {
            int status = http.getStatusCode().value();
            log.warn("[mtn-api] operation={} upstreamHttpStatus={}", operationName, status);
            if (status == 400 || status == 422) {
                return PaymentIntegrationException.schoolValidation("El colegio rechazó los datos enviados para el pago");
            }
            if (status == 401 || status == 403) return PaymentIntegrationException.auth("La API MTN rechazó el token o el scope ADMISION");
            if (status == 404) return PaymentIntegrationException.unavailable("El cobro no existe en el sistema del colegio");
            return PaymentIntegrationException.unavailable("La API MTN respondió con error HTTP " + status);
        }
        if (ex instanceof ResourceAccessException) {
            return PaymentIntegrationException.unavailable("La API MTN no respondió dentro del tiempo configurado");
        }
        return PaymentIntegrationException.unavailable("No fue posible comunicarse con la API MTN");
    }

    private <T> T traced(String operation, String method, String configuredPath, Supplier<T> request) {
        long startedAt = System.nanoTime();
        String requestPath = path(configuredPath, "");
        log.info("[mtn-api] operation={} method={} path={} started=true", operation, method, requestPath);
        try {
            T response = request.get();
            log.info("[mtn-api] operation={} method={} path={} completed=true durationMs={}",
                operation, method, requestPath, elapsedMillis(startedAt));
            return response;
        } catch (PaymentIntegrationException ex) {
            log.warn("[mtn-api] operation={} method={} path={} completed=false code={} durationMs={}",
                operation, method, requestPath, ex.code(), elapsedMillis(startedAt));
            throw ex;
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://localhost";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String path(String configured, String fallback) {
        String result = configured == null || configured.isBlank() ? fallback : configured.trim();
        return result.startsWith("/") ? result : "/" + result;
    }

    private static SimpleClientHttpRequestFactory requestFactory(MtnAdmissionProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout() == null ? Duration.ofSeconds(3) : properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout() == null ? Duration.ofSeconds(10) : properties.readTimeout());
        return factory;
    }
}
