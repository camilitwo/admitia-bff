package cl.mtn.admitiabff.service.payments;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class TokuSignatureVerifier {
    public boolean isValid(String header, String eventId, String secret, long toleranceSeconds) {
        if (header == null || header.isBlank() || eventId == null || eventId.isBlank() || secret == null || secret.isBlank()) {
            return false;
        }
        String timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && "t".equals(pair[0])) timestamp = pair[1];
            if (pair.length == 2 && "s".equals(pair[0])) signature = pair[1];
        }
        if (timestamp == null || signature == null) return false;
        try {
            long signedAt = Long.parseLong(timestamp);
            if (toleranceSeconds > 0 && Math.abs(Instant.now().getEpochSecond() - signedAt) > toleranceSeconds) {
                return false;
            }
            String expected = hmacSha256(secret, timestamp + "." + eventId);
            return constantTimeEquals(expected, signature);
        } catch (Exception ex) {
            return false;
        }
    }

    private String hmacSha256(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte b : digest) result.append(String.format("%02x", b));
        return result.toString();
    }

    private boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) return false;
        int result = 0;
        for (int i = 0; i < left.length; i++) result |= left[i] ^ right[i];
        return result == 0;
    }
}
