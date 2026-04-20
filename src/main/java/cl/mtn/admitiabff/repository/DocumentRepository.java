package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.common.DocumentApprovalStatus;
import cl.mtn.admitiabff.domain.document.DocumentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {
    List<DocumentEntity> findByApplicationIdOrderByUploadDateDesc(Long applicationId);
    long countByApplicationId(Long applicationId);
    long countByApplicationIdAndApprovalStatus(Long applicationId, DocumentApprovalStatus status);
}
