package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.common.PaymentStatus;
import cl.mtn.admitiabff.domain.payment.PaymentEntity;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {
    Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey);
    Optional<PaymentEntity> findFirstByApplicationIdAndStatusInOrderByCreatedAtDesc(Long applicationId, Collection<PaymentStatus> statuses);
    Optional<PaymentEntity> findFirstByGuardianUserIdAndProviderCustomerIdIsNotNullOrderByCreatedAtDesc(Long guardianUserId);
    Optional<PaymentEntity> findFirstByProviderInvoiceIdOrderByCreatedAtDesc(String providerInvoiceId);
}
