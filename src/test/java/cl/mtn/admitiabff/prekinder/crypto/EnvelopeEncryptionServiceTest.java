package cl.mtn.admitiabff.prekinder.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cl.mtn.admitiabff.prekinder.config.PrekinderProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class EnvelopeEncryptionServiceTest {
    private final EnvelopeEncryptionService encryption = new EnvelopeEncryptionService(new PrekinderProperties(
        true, null, new PrekinderProperties.Encryption("V1", Base64.getEncoder().encodeToString(new byte[32])), null, null
    ));

    @Test
    void encryptsWithUniqueDekAndNonceAndAuthenticatesAad() {
        var first = encryption.encrypt("observación sensible", "prod|comments|1|field");
        var second = encryption.encrypt("observación sensible", "prod|comments|1|field");

        assertNotEquals(first.ciphertext(), second.ciphertext());
        assertNotEquals(first.iv(), second.iv());
        assertEquals("observación sensible", encryption.decrypt(first, "prod|comments|1|field"));
        assertThrows(IllegalStateException.class, () -> encryption.decrypt(first, "prod|comments|2|field"));
    }
}
