package cl.mtn.admitiabff.service.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class MtnAdmissionTokenProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void cachesBasicTokenUntilItsUsableExpiry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            calls.incrementAndGet();
            String expected = "Basic " + Base64.getEncoder().encodeToString("ADMISION:secret".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"access_token\":\"token-qa\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        });
        MtnAdmissionTokenProvider provider = new MtnAdmissionTokenProvider(RestClient.builder(), properties(MtnAdmissionProperties.ClientAuthMethod.BASIC));

        assertEquals("token-qa", provider.accessToken());
        assertEquals("token-qa", provider.accessToken());
        assertEquals(1, calls.get());
    }

    @Test
    void sendsCredentialsInFormWhenConfigured() throws Exception {
        server = server(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("grant_type=client_credentials"));
            assertTrue(body.contains("client_id=ADMISION"));
            assertTrue(body.contains("client_secret=secret"));
            respond(exchange, 200, "{\"access_token\":\"form-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        });
        MtnAdmissionTokenProvider provider = new MtnAdmissionTokenProvider(RestClient.builder(), properties(MtnAdmissionProperties.ClientAuthMethod.FORM));

        assertEquals("form-token", provider.accessToken());
    }

    @Test
    void concurrentRequestsShareOneTokenRefresh() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "{\"access_token\":\"shared-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        });
        MtnAdmissionTokenProvider provider = new MtnAdmissionTokenProvider(RestClient.builder(), properties(MtnAdmissionProperties.ClientAuthMethod.BASIC));
        var executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> (Callable<String>) provider::accessToken)
                .toList();
            for (var result : executor.invokeAll(tasks)) assertEquals("shared-token", result.get());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, calls.get());
    }

    private HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer result = HttpServer.create(new InetSocketAddress(0), 0);
        result.createContext("/auth/token", exchange -> handler.handle(exchange));
        result.start();
        return result;
    }

    private MtnAdmissionProperties properties(MtnAdmissionProperties.ClientAuthMethod method) {
        return new MtnAdmissionProperties(true, "http://localhost:" + server.getAddress().getPort(), "/auth/token",
            "/admision/apoderados", "/admision/cobros", "ADMISION", "secret", method, true,
            Duration.ofSeconds(2), Duration.ofSeconds(2), new java.math.BigDecimal("50000"), "CLP", 3,
            "POSTMAN-QA", "Santiago", "America/Santiago");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler { void handle(HttpExchange exchange) throws IOException; }
}
