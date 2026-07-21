package cl.mtn.admitiabff.service.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.AdmissionRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.StudentRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class MtnAdmissionClientContractTest {
    private HttpServer server;
    private MtnAdmissionClient client;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger chargeCalls = new AtomicInteger();
    private volatile boolean rejectFirstCharge;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/auth/token", exchange -> {
            tokenCalls.incrementAndGet();
            respond(exchange, 200, "{\"access_token\":\"contract-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        });
        server.createContext("/admision/apoderados", exchange -> {
            assertEquals("Bearer contract-token", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"ok\":true,\"estado\":\"OK\",\"errores\":[],\"advertencias\":[],\"c_bpartner_id\":105,\"c_bpartner_location_id\":106,\"apoderado_estado\":\"creado\",\"toku_customer_id\":\"cus_1\",\"toku_customer_estado\":\"creado\",\"alumnos\":[{\"ad_user_id\":205,\"estado\":\"creado\",\"toku_subscription_id\":\"sub_1\",\"toku_subscription_estado\":\"creado\"}]}");
        });
        server.createContext("/admision/cobros", exchange -> {
            chargeCalls.incrementAndGet();
            assertEquals("Bearer contract-token", exchange.getRequestHeaders().getFirst("Authorization"));
            if (rejectFirstCharge) {
                rejectFirstCharge = false;
                respond(exchange, 401, "{\"error\":\"invalid_token\"}");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "{\"encontrado\":true,\"c_orderpayschedule_id\":301,\"pagado\":false,\"estado\":\"PENDIENTE\",\"monto\":50000,\"moneda\":\"CLP\",\"link_pago\":\"https://pay.example/301\"}");
            } else {
                respond(exchange, 200, "{\"ok\":true,\"estado\":\"OK\",\"c_orderpayschedule_id\":301,\"toku_invoice_id\":\"inv_301\",\"link_pago\":\"https://pay.example/301\",\"monto\":50000,\"moneda\":\"CLP\",\"fecha_vencimiento\":\"2026-08-15\",\"estado_pago\":\"PENDIENTE\"}");
            }
        });
        server.start();
        MtnAdmissionProperties properties = properties();
        MtnAdmissionTokenProvider tokenProvider = new MtnAdmissionTokenProvider(RestClient.builder(), properties);
        client = new MtnAdmissionClient(RestClient.builder(), properties, tokenProvider);
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    @Test
    void mapsAdmissionChargeAndStatusContracts() {
        var admission = client.synchronizeAdmission(new AdmissionRequest("12345678", "5", "Juan", null, null, null, null, "Santiago", null,
            List.of(new StudentRequest("11111111", "1", "Ana", "1A"))));
        assertEquals(105L, admission.businessPartnerId());
        assertEquals(205L, admission.alumnos().get(0).userId());

        var charge = client.createCharge(new ChargeRequest("12345678", "5", "Juan", null, null, "11111111", "1", "Ana", "1A",
            new BigDecimal("50000"), "CLP", "2026-08-15", "Postulación", "ADMITIA-1"));
        assertEquals(301L, charge.chargeId());
        assertNotNull(charge.paymentLink());

        var status = client.chargeStatus(301L);
        assertEquals("PENDIENTE", status.estado());
        assertEquals(1, tokenCalls.get());
    }

    @Test
    void refreshesTokenOnceAfterBusinessRequestReturnsUnauthorized() {
        rejectFirstCharge = true;
        var charge = client.createCharge(new ChargeRequest("12345678", "5", "Juan", null, null, "11111111", "1", "Ana", "1A",
            new BigDecimal("50000"), "CLP", "2026-08-15", "Postulación", "ADMITIA-1"));

        assertEquals(301L, charge.chargeId());
        assertEquals(2, tokenCalls.get());
        assertEquals(2, chargeCalls.get());
    }

    private MtnAdmissionProperties properties() {
        return new MtnAdmissionProperties(true, "http://localhost:" + server.getAddress().getPort(), "/auth/token",
            "/admision/apoderados", "/admision/cobros", "ADMISION", "secret", MtnAdmissionProperties.ClientAuthMethod.BASIC, true,
            Duration.ofSeconds(2), Duration.ofSeconds(2), new BigDecimal("50000"), "CLP", 3,
            "ADMITIA", "Santiago", "America/Santiago");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
