package cl.mtn.admitiabff.config;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RsaKeyService {
    private static final int KEY_SIZE = 2048;
    private static final int AUTH_TAG_LENGTH = 128;
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA256,
        PSource.PSpecified.DEFAULT
    );

    private PrivateKey privateKey;
    private PublicKey publicKey;
    private final Path keyDirectory;

    public RsaKeyService(@Value("${app.uploads-dir}") String uploadsDir) {
        this.keyDirectory = Path.of(uploadsDir, ".keys");
    }

    @PostConstruct
    void init() {
        try {
            loadOrCreateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar la llave RSA", ex);
        }
    }

    public String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(publicKey.getEncoded()) + "\n-----END PUBLIC KEY-----";
    }

    public Map<String, Object> publicKeyInfo() {
        String hash = publicKeyHash();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("publicKey", publicKeyPem());
        data.put("keyId", hash.substring(0, 16));
        data.put("algorithm", "RSA-OAEP-256/AES-256-GCM");
        data.put("keySize", KEY_SIZE);
        data.put("hash", hash);
        data.put("expiresIn", 3600);
        return data;
    }

    public String decryptIfNeeded(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (!value.matches("^[A-Za-z0-9+/=\\r\\n]+$") || value.length() < 80) {
            return value;
        }
        try {
            byte[] encrypted = decodeBase64(value);
            byte[] decrypted = decryptRsaOaep(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            try {
                Cipher cipher = Cipher.getInstance("RSA");
                cipher.init(Cipher.DECRYPT_MODE, privateKey);
                byte[] decrypted = cipher.doFinal(decodeBase64(value));
                return new String(decrypted, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return value;
            }
        }
    }

    public String decryptHybridPayload(Map<String, Object> payload) {
        try {
            byte[] encryptedKey = decodeBase64(String.valueOf(payload.get("encryptedKey")));
            byte[] encryptedData = decodeBase64(String.valueOf(payload.get("encryptedData")));
            byte[] iv = decodeBase64(String.valueOf(payload.get("iv")));
            byte[] authTag = decodeBase64(String.valueOf(payload.get("authTag")));
            byte[] aesKey = decryptRsaOaep(encryptedKey);

            byte[] encryptedMessage = new byte[encryptedData.length + authTag.length];
            System.arraycopy(encryptedData, 0, encryptedMessage, 0, encryptedData.length);
            System.arraycopy(authTag, 0, encryptedMessage, encryptedData.length, authTag.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(AUTH_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encryptedMessage);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalArgumentException("No se pudo descifrar el payload de autenticación", ex);
        }
    }

    private byte[] decryptRsaOaep(byte[] encrypted) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP_SHA256);
        return cipher.doFinal(encrypted);
    }

    private byte[] decodeBase64(String value) {
        return Base64.getDecoder().decode(value.replaceAll("\\s", ""));
    }

    private String publicKeyHash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo calcular el hash de la llave pública", ex);
        }
    }

    private void loadOrCreateKeyPair() throws Exception {
        Files.createDirectories(keyDirectory);
        Path privateKeyPath = keyDirectory.resolve("rsa-private.pem");
        Path publicKeyPath = keyDirectory.resolve("rsa-public.pem");
        if (Files.exists(privateKeyPath) && Files.exists(publicKeyPath)) {
            this.privateKey = loadPrivateKey(privateKeyPath);
            this.publicKey = loadPublicKey(publicKeyPath);
            return;
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(KEY_SIZE);
        KeyPair keyPair = generator.generateKeyPair();
        this.privateKey = keyPair.getPrivate();
        this.publicKey = keyPair.getPublic();
        writePem(privateKeyPath, "PRIVATE KEY", privateKey.getEncoded());
        writePem(publicKeyPath, "PUBLIC KEY", publicKey.getEncoded());
    }

    private PrivateKey loadPrivateKey(Path path) throws Exception {
        byte[] encoded = readPem(path, "PRIVATE KEY");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private PublicKey loadPublicKey(Path path) throws Exception {
        byte[] encoded = readPem(path, "PUBLIC KEY");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    }

    private void writePem(Path path, String type, byte[] encoded) throws Exception {
        String pem = "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(encoded)
            + "\n-----END " + type + "-----\n";
        Files.writeString(path, pem, StandardCharsets.UTF_8);
    }

    private byte[] readPem(Path path, String type) throws Exception {
        String pem = Files.readString(path, StandardCharsets.UTF_8);
        String normalized = pem
            .replace("-----BEGIN " + type + "-----", "")
            .replace("-----END " + type + "-----", "")
            .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }
}
