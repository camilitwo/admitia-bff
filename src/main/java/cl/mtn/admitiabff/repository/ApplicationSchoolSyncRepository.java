package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.payment.ApplicationSchoolSyncEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationSchoolSyncRepository extends JpaRepository<ApplicationSchoolSyncEntity, Long> {
    Optional<ApplicationSchoolSyncEntity> findByApplicationId(Long applicationId);
}
