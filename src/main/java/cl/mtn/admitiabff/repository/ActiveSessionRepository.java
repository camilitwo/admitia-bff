package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.user.ActiveSessionEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActiveSessionRepository extends JpaRepository<ActiveSessionEntity, Long> {
    void deleteByUser(UserEntity user);
    Optional<ActiveSessionEntity> findByTokenHash(String tokenHash);
}
