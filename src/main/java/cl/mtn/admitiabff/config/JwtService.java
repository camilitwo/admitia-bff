package cl.mtn.admitiabff.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Servicio de emisión y validación de JWT propios del BFF.
 *
 * Mejoras (ver docs/SECURITY_TOKENS.md):
 *  - Validación estricta de la longitud del secret (>= 32 bytes).
 *  - Claims estándar: iss, aud, jti, iat, nbf, exp + typ=access.
 *  - Algoritmo fijo HS256 con verificación explícita en el parser.
 *  - Clock skew tolerable de 30 s.
 *  - TTL del access token configurable (15 min por defecto).
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration-minutes:15}")
    private long expirationMinutes;

    @Value("${app.jwt.issuer:admitia-bff}")
    private String issuer;

    @Value("${app.jwt.audience:admitia-frontend}")
    private String audience;

    @Value("${app.jwt.clock-skew-seconds:30}")
    private long clockSkewSeconds;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secret);
        } catch (IllegalArgumentException ex) {
            keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                "APP_JWT_SECRET inseguro: requiere al menos 32 bytes (256 bits). "
                + "Genera uno con: openssl rand -base64 48");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("[JWT] Servicio inicializado: issuer={} audience={} ttl={}min", issuer, audience, expirationMinutes);
    }

    /** Resultado de emitir un access token con metadatos para devolver al cliente. */
    public record IssuedToken(String token, String jti, long expiresInSeconds, Instant issuedAt, Instant expiresAt) {}

    public IssuedToken issueAccessToken(Long userId, String email, String role) {
        Instant now = Instant.now();
        Instant exp = now.plus(expirationMinutes, ChronoUnit.MINUTES);
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
            .id(jti)
            .issuer(issuer)
            .audience().add(audience).and()
            .subject(String.valueOf(userId))
            .claim("email", email)
            .claim("role", role)
            .claim("typ", "access")
            .issuedAt(Date.from(now))
            .notBefore(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(signingKey, Jwts.SIG.HS256)
            .compact();
        return new IssuedToken(token, jti, expirationMinutes * 60L, now, exp);
    }

    /** Compatibilidad hacia atrás (devuelve sólo la cadena del token). */
    public String generateToken(Long userId, String email, String role) {
        return issueAccessToken(userId, email, role).token();
    }

    public long getAccessTokenSeconds() {
        return expirationMinutes * 60L;
    }

    public Claims extractAllClaims(String token) {
        return parser().parseSignedClaims(token).getPayload();
    }

    public boolean isValid(String token) {
        try {
            parser().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("[JWT] Token inválido: {}", ex.getMessage());
            return false;
        }
    }

    private io.jsonwebtoken.JwtParser parser() {
        return Jwts.parser()
            .verifyWith(signingKey)
            .requireIssuer(issuer)
            .requireAudience(audience)
            .clockSkewSeconds(clockSkewSeconds)
            .build();
    }

    public String getIssuer() { return issuer; }
    public String getAudience() { return audience; }
}
