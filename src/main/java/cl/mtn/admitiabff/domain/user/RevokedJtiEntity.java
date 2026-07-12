package cl.mtn.admitiabff.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * Blacklist de access tokens (jti) revocados antes de su expiración natural.
 */
@Getter
@Setter
@Entity
@Table(name = "revoked_jtis")
public class RevokedJtiEntity {

    @Id
    @Column(name = "jti", length = 64)
    private String jti;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "revoked_at", nullable = false)
    private LocalDateTime revokedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "reason", length = 64)
    private String reason;
}

