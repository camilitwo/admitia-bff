package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.application.ComplementaryFormEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplementaryFormRepository extends JpaRepository<ComplementaryFormEntity, Long> {
    Optional<ComplementaryFormEntity> findByApplicationId(Long applicationId);
}
