package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.user.RefreshTokenEntity;
import cl.mtn.admitiabff.domain.user.RevokedJtiEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.RefreshTokenRepository;
import cl.mtn.admitiabff.repository.RevokedJtiRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestor de refresh tokens rotativos y blacklist de jti.
 *
 *  - Los refresh tokens son OPACOS (cadenas aleatorias de 256 bits) hasheados con SHA-256
 *    antes de persistirse. El cliente recibe el valor en claro UNA sola vez.
 *  - Cada refresh pertenece a una "familia". Si llega un refresh ya usado/revocado, se
 *    revoca toda la familia (detección de robo).
 *  - La revocación de un access token se hace agregando su jti a {@link RevokedJtiEntity}
 *    con TTL = exp del access token.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final RevokedJtiRepository revokedJtiRepository;

    @Value("${app.jwt.refresh-token-minutes:480}")
    private long refreshTokenMinutes;

    public TokenService(RefreshTokenRepository refreshTokenRepository,
                        RevokedJtiRepository revokedJtiRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.revokedJtiRepository = revokedJtiRepository;
    }

    public long getRefreshTokenMinutes() { return refreshTokenMinutes; }

    public record IssuedRefresh(String token, String familyId, LocalDateTime expiresAt, long expiresInSeconds, UserEntity user) {}

    /** Emite un nuevo refresh token (familia nueva, tras login). */
    @Transactional
    public IssuedRefresh issueNewFamily(UserEntity user, String userAgent, String ipAddress) {
        return persistRefresh(user, UUID.randomUUID().toString(), null, userAgent, ipAddress);
    }

    /**
     * Rota un refresh existente: marca el actual como ROTATED y emite uno nuevo en la misma familia.
     * Si el refresh ya estaba revocado/usado → detección de robo: revoca toda la familia.
     */
    @Transactional
    public IssuedRefresh rotate(String rawRefreshToken, String userAgent, String ipAddress) {
        String hash = sha256(rawRefreshToken);
        RefreshTokenEntity current = refreshTokenRepository.findByTokenHash(hash)
            .orElseThrow(() -> new InvalidRefreshException("REFRESH_INVALID", "Refresh token desconocido"));

        LocalDateTime now = LocalDateTime.now();

        if (current.getRevokedAt() != null) {
            // Reuso → comprometido. Revocar toda la familia.
            log.warn("[Refresh] REUSE detectado para familyId={} userId={}", current.getFamilyId(), current.getUser().getId());
            refreshTokenRepository.revokeFamily(current.getFamilyId(), now, "STOLEN");
            throw new InvalidRefreshException("SESSION_REVOKED",
                "Refresh token reutilizado. Sesión revocada por seguridad.");
        }
        if (current.getExpiresAt().isBefore(now)) {
            current.setRevokedAt(now);
            current.setRevokedReason("EXPIRED");
            refreshTokenRepository.save(current);
            throw new InvalidRefreshException("SESSION_EXPIRED", "Refresh token expirado");
        }

        // Marcar el actual como ROTATED y emitir el siguiente en la misma familia.
        current.setRevokedAt(now);
        current.setRevokedReason("ROTATED");
        current.setLastUsedAt(now);
        refreshTokenRepository.save(current);

        return persistRefresh(current.getUser(), current.getFamilyId(), current.getId(), userAgent, ipAddress);
    }

    @Transactional
    public void revokeRefresh(String rawRefreshToken, String reason) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) return;
        String hash = sha256(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            token.setRevokedAt(LocalDateTime.now());
            token.setRevokedReason(reason);
            refreshTokenRepository.save(token);
        });
    }

    @Transactional
    public void revokeAllForUser(UserEntity user, String reason) {
        refreshTokenRepository.revokeAllForUser(user, LocalDateTime.now(), reason);
    }

    /** Agrega un jti a la blacklist (con TTL = exp del access token). */
    @Transactional
    public void blacklistJti(String jti, Long userId, Instant expiresAt, String reason) {
        if (jti == null || jti.isBlank()) return;
        if (revokedJtiRepository.existsByJti(jti)) return;
        RevokedJtiEntity entity = new RevokedJtiEntity();
        entity.setJti(jti);
        entity.setUserId(userId);
        entity.setRevokedAt(LocalDateTime.now());
        entity.setExpiresAt(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        entity.setReason(reason);
        revokedJtiRepository.save(entity);
    }

    public boolean isJtiRevoked(String jti) {
        return jti != null && revokedJtiRepository.existsByJti(jti);
    }

    /** Hash SHA-256 (hex) usado para almacenar el refresh token. */
    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private IssuedRefresh persistRefresh(UserEntity user, String familyId, Long parentId, String userAgent, String ipAddress) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime exp = now.plusMinutes(refreshTokenMinutes);

        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setUser(user);
        entity.setTokenHash(sha256(token));
        entity.setFamilyId(familyId);
        entity.setParentId(parentId);
        entity.setIssuedAt(now);
        entity.setExpiresAt(exp);
        entity.setUserAgent(userAgent);
        entity.setIpAddress(ipAddress);
        refreshTokenRepository.save(entity);

        return new IssuedRefresh(token, familyId, exp, refreshTokenMinutes * 60L, user);
    }

    /** Excepción dedicada para mapear códigos de error específicos al cliente. */
    public static class InvalidRefreshException extends RuntimeException {
        private final String code;
        public InvalidRefreshException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String getCode() { return code; }
    }
}






