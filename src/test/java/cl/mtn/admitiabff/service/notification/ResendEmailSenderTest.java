package cl.mtn.admitiabff.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResendEmailSenderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsIdempotencyHeaderAndReturnsProviderId() throws Exception {
        AtomicReference<String> idempotency = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"id\":\"resend-123\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        ResendEmailSender sender = new ResendEmailSender("re_test",
            "http://127.0.0.1:" + server.getAddress().getPort(), "Admisiones <admisiones@example.cl>");

        String messageId = sender.send("familia@example.cl", "Recordatorio", "<p>Pago pendiente</p>",
            "application-reminder/2026-08-24/10/PAYMENT_REMINDER");

        assertThat(messageId).isEqualTo("resend-123");
        assertThat(idempotency.get()).isEqualTo("application-reminder/2026-08-24/10/PAYMENT_REMINDER");
        assertThat(requestBody.get()).contains("familia@example.cl", "Pago pendiente", "html", "text");
    }
}
