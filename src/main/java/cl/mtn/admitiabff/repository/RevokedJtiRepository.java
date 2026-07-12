package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.user.RevokedJtiEntity;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RevokedJtiRepository extends JpaRepository<RevokedJtiEntity, String> {
    boolean existsByJti(String jti);

    @Modifying
    @Query("delete from RevokedJtiEntity r where r.expiresAt < :before")
    int purgeExpired(@Param("before") LocalDateTime before);
}

