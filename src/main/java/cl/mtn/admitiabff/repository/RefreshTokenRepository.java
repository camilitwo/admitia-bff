package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.user.RefreshTokenEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshTokenEntity r set r.revokedAt = :now, r.revokedReason = :reason "
        + "where r.familyId = :familyId and r.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId,
                     @Param("now") LocalDateTime now,
                     @Param("reason") String reason);

    @Modifying
    @Query("update RefreshTokenEntity r set r.revokedAt = :now, r.revokedReason = :reason "
        + "where r.user = :user and r.revokedAt is null")
    int revokeAllForUser(@Param("user") UserEntity user,
                         @Param("now") LocalDateTime now,
                         @Param("reason") String reason);

    @Modifying
    @Query("delete from RefreshTokenEntity r where r.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}

