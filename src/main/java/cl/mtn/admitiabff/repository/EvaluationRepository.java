package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.evaluation.EvaluationEntity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvaluationRepository extends JpaRepository<EvaluationEntity, Long> {
    List<EvaluationEntity> findAllByOrderByCreatedAtDesc();
    List<EvaluationEntity> findByApplicationIdOrderByCreatedAtDesc(Long applicationId);
    List<EvaluationEntity> findByEvaluatorIdOrderByCreatedAtDesc(Long evaluatorId);
    List<EvaluationEntity> findByEvaluatorIdAndStatusInOrderByCreatedAtDesc(Long evaluatorId, List<EvaluationStatus> statuses);
    List<EvaluationEntity> findByEvaluatorIdAndStatusOrderByCreatedAtDesc(Long evaluatorId, EvaluationStatus status);
    List<EvaluationEntity> findByEvaluationTypeOrderByCreatedAtDesc(String evaluationType);
    List<EvaluationEntity> findBySubjectOrderByCreatedAtDesc(String subject);
    Optional<EvaluationEntity> findById(Long id);
    long countByStatusIn(List<EvaluationStatus> statuses);
    @Query("select e.status as key, count(e) as total from EvaluationEntity e group by e.status")
    List<KeyCountView> countByStatus();
    @Query("select e.evaluationType as key, count(e) as total from EvaluationEntity e group by e.evaluationType")
    List<KeyCountView> countByType();
    @Query("select coalesce(avg(e.score), 0) from EvaluationEntity e where e.score is not null")
    BigDecimal averageScore();
    @Query("select e from EvaluationEntity e left join fetch e.evaluator where e.status in :statuses order by e.createdAt desc")
    List<EvaluationEntity> findAssignments(@Param("statuses") List<EvaluationStatus> statuses);
    @Query("select e from EvaluationEntity e where e.application.id = :applicationId and e.evaluationType = 'FAMILY_INTERVIEW'")
    List<EvaluationEntity> findFamilyInterviewByApplicationId(@Param("applicationId") Long applicationId);

    interface KeyCountView {
        String getKey();
        long getTotal();
    }
}
