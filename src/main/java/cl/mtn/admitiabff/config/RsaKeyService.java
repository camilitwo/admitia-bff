package cl.mtn.admitiabff.config;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import javax.crypto.Cipher;
import org.springframework.stereotype.Service;

@Service
public class RsaKeyService {
    private PrivateKey privateKey;
    private PublicKey publicKey;

    @PostConstruct
    void init() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            this.privateKey = keyPair.getPrivate();
            this.publicKey = keyPair.getPublic();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar la llave RSA", ex);
        }
    }

    public String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(publicKey.getEncoded()) + "\n-----END PUBLIC KEY-----";
    }

    public String decryptIfNeeded(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (!value.matches("^[A-Za-z0-9+/=\\r\\n]+$") || value.length() < 80) {
            return value;
        }
        try {
            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(value.replaceAll("\\s", "")));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return value;
        }
    }
}
