package cl.mtn.admitiabff.service.payments;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class TokuSignatureVerifierTest {
    private final TokuSignatureVerifier verifier = new TokuSignatureVerifier();

    @Test
    void validatesSignedWebhookHeader() throws Exception {
        String secret = "whesec_test";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String eventId = "evt_123";
        String signature = hmac(secret, timestamp + "." + eventId);

        assertTrue(verifier.isValid("t=" + timestamp + ",s=" + signature, eventId, secret, 300));
    }

    @Test
    void rejectsInvalidSignature() {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        assertFalse(verifier.isValid("t=" + timestamp + ",s=bad", "evt_123", "whesec_test", 300));
    }

    private String hmac(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte b : digest) result.append(String.format("%02x", b));
        return result.toString();
    }
}
