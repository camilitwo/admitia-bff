package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.common.PaymentStatus;
import cl.mtn.admitiabff.domain.payment.PaymentEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {
    Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey);
    Optional<PaymentEntity> findFirstByApplicationIdOrderByCreatedAtDesc(Long applicationId);
    List<PaymentEntity> findByGuardianUserIdAndStatusAndInstitutionalChargeIdIsNotNull(Long guardianUserId, PaymentStatus status);
}
