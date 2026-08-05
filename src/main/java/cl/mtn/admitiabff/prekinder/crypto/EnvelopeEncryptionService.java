package cl.mtn.admitiabff.prekinder.crypto;

import cl.mtn.admitiabff.prekinder.config.PrekinderProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class EnvelopeEncryptionService {
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private final SecureRandom random = new SecureRandom();
    private final String activeVersion;
    private final PrekinderProperties properties;

    public EnvelopeEncryptionService(PrekinderProperties properties) {
        this.properties = properties;
        this.activeVersion = require(properties.encryption().activeVersion(), "PREKINDER_ENCRYPTION_ACTIVE_VERSION");
        resolveKek(activeVersion);
    }

    public EncryptedPayload encrypt(String plaintext, String aad) {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256, random);
            SecretKey dek = generator.generateKey();
            byte[] contentIv = nonce();
            byte[] wrappedDekIv = nonce();
            byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, dek, contentIv, plaintext.getBytes(StandardCharsets.UTF_8), aad);
            byte[] wrappedDek = crypt(Cipher.ENCRYPT_MODE, resolveKek(activeVersion), wrappedDekIv, dek.getEncoded(), wrapAad(aad));
            return new EncryptedPayload(
                encode(ciphertext), encode(contentIv), encode(wrappedDek), encode(wrappedDekIv), activeVersion
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No fue posible cifrar el dato sensible", exception);
        }
    }

    public String decrypt(EncryptedPayload payload, String aad) {
        try {
            SecretKey kek = resolveKek(payload.keyVersion());
            byte[] dekBytes = crypt(Cipher.DECRYPT_MODE, kek, decode(payload.wrappedDekIv()), decode(payload.wrappedDek()), wrapAad(aad));
            SecretKey dek = new SecretKeySpec(dekBytes, "AES");
            byte[] plaintext = crypt(Cipher.DECRYPT_MODE, dek, decode(payload.iv()), decode(payload.ciphertext()), aad);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("El dato sensible no pudo autenticarse", exception);
        }
    }

    private SecretKey resolveKek(String version) {
        String envName = "PREKINDER_ENCRYPTION_KEY_" + version.toUpperCase(Locale.ROOT);
        String encoded = System.getenv(envName);
        if ((encoded == null || encoded.isBlank()) && "V1".equalsIgnoreCase(version)) {
            encoded = properties.encryption().keyV1();
        }
        byte[] key = decode(require(encoded, envName));
        if (key.length != 32) {
            throw new IllegalStateException(envName + " debe contener exactamente 32 bytes en Base64");
        }
        return new SecretKeySpec(key, "AES");
    }

    private byte[] crypt(int mode, SecretKey key, byte[] iv, byte[] input, String aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(input);
    }

    private byte[] nonce() {
        byte[] result = new byte[NONCE_BYTES];
        random.nextBytes(result);
        return result;
    }

    private static String wrapAad(String aad) { return "dek|" + aad; }
    private static String encode(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private static byte[] decode(String value) { return Base64.getDecoder().decode(value); }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " es obligatorio");
        return value.trim();
    }
}
