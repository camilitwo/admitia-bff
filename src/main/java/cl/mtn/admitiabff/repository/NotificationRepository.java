package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.common.NotificationChannel;
import cl.mtn.admitiabff.domain.common.NotificationStatus;
import cl.mtn.admitiabff.domain.notification.NotificationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {
    @Query("select n from NotificationEntity n where (:recipientType is null or n.recipientType = cast(:recipientType as string)) and (:recipientId is null or n.recipientId = :recipientId) and (:channel is null or n.channel = :channel) and (:type is null or n.type = cast(:type as string)) and (:status is null or n.status = :status)")
    Page<NotificationEntity> search(@Param("recipientType") String recipientType, @Param("recipientId") Long recipientId, @Param("channel") NotificationChannel channel, @Param("type") String type, @Param("status") NotificationStatus status, Pageable pageable);
}
