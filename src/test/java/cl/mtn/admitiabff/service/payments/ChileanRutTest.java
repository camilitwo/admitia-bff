package cl.mtn.admitiabff.service.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChileanRutTest {
    @Test
    void normalizesAndValidatesRut() {
        ChileanRut.Parts parts = ChileanRut.parse("12.345.678-5", "apoderado");
        assertEquals("12345678", parts.body());
        assertEquals("5", parts.verifier());
    }

    @Test
    void rejectsInvalidVerifier() {
        PaymentIntegrationException error = assertThrows(PaymentIntegrationException.class,
            () -> ChileanRut.parse("12.345.678-0", "apoderado"));
        assertEquals("PAYMENT_DATA_INVALID", error.code());
    }
}
