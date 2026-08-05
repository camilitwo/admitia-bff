package cl.mtn.admitiabff.prekinder.crypto;

public record EncryptedPayload(
    String ciphertext,
    String iv,
    String wrappedDek,
    String wrappedDekIv,
    String keyVersion
) {}
