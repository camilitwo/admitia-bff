package cl.mtn.admitiabff.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResendEmailSenderIdempotencyTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsDeterministicIdempotencyHeaderToProvider() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/emails", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"id\":\"email-123\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ResendEmailSender sender = new ResendEmailSender(
                "re_test", "http://127.0.0.1:" + server.getAddress().getPort(), "Admisión <admision@mtn.cl>");
        String providerId = sender.send(
                "apoderado@example.cl", "Resultado", "<p>Resultado</p>", "admission-result/2027/42");

        assertEquals("email-123", providerId);
        assertEquals("admission-result/2027/42", header.get());
    }

    @Test
    void treatsSuccessfulResponseWithoutProviderIdAsAmbiguous() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/emails", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ResendEmailSender sender = new ResendEmailSender(
                "re_test", "http://127.0.0.1:" + server.getAddress().getPort(), "Admisión <admision@mtn.cl>");

        ResendEmailSender.ResendDeliveryException exception = assertThrows(
                ResendEmailSender.ResendDeliveryException.class,
                () -> sender.send(
                        "apoderado@example.cl", "Resultado", "<p>Resultado</p>", "admission-result/2027/42"));

        assertTrue(exception.isDeliveryUnknown());
        assertTrue(exception.isRetryable());
    }

    @Test
    void retriesConcurrentIdempotentRequestWithoutLoggingProviderBody() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/emails", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"name\":\"concurrent_idempotent_requests\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(409, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ResendEmailSender sender = new ResendEmailSender(
                "re_test", "http://127.0.0.1:" + server.getAddress().getPort(), "Admisión <admision@mtn.cl>");

        ResendEmailSender.ResendDeliveryException exception = assertThrows(
                ResendEmailSender.ResendDeliveryException.class,
                () -> sender.send(
                        "apoderado@example.cl", "Resultado", "<p>Resultado</p>", "admission-result/2027/42"));

        assertEquals(409, exception.getHttpStatus());
        assertTrue(exception.isRetryable());
    }

    @Test
    void doesNotRetryIdempotencyConflictWithDifferentPayload() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/emails", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"name\":\"invalid_idempotent_request\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ResendEmailSender sender = new ResendEmailSender(
                "re_test", "http://127.0.0.1:" + server.getAddress().getPort(), "Admisión <admision@mtn.cl>");

        ResendEmailSender.ResendDeliveryException exception = assertThrows(
                ResendEmailSender.ResendDeliveryException.class,
                () -> sender.send(
                        "apoderado@example.cl", "Resultado", "<p>Resultado distinto</p>", "admission-result/2027/42"));

        assertEquals(409, exception.getHttpStatus());
        assertFalse(exception.isRetryable());
    }
}
