package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.payment.PaymentEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEventEntity, Long> {
    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);
}
