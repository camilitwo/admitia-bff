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
    @Query("select e from EvaluationEntity e where e.evaluator.id = :evaluatorId and e.application.deletedAt is null and e.application.archived = false order by e.createdAt desc")
    List<EvaluationEntity> findByEvaluatorIdOrderByCreatedAtDesc(@Param("evaluatorId") Long evaluatorId);
    @Query("select e from EvaluationEntity e where e.evaluator.id = :evaluatorId and e.status in :statuses and e.application.deletedAt is null and e.application.archived = false order by e.createdAt desc")
    List<EvaluationEntity> findByEvaluatorIdAndStatusInOrderByCreatedAtDesc(@Param("evaluatorId") Long evaluatorId, @Param("statuses") List<EvaluationStatus> statuses);
    @Query("select e from EvaluationEntity e where e.evaluator.id = :evaluatorId and e.status = :status and e.application.deletedAt is null and e.application.archived = false order by e.createdAt desc")
    List<EvaluationEntity> findByEvaluatorIdAndStatusOrderByCreatedAtDesc(@Param("evaluatorId") Long evaluatorId, @Param("status") EvaluationStatus status);
    List<EvaluationEntity> findByEvaluationTypeOrderByCreatedAtDesc(String evaluationType);
    List<EvaluationEntity> findBySubjectOrderByCreatedAtDesc(String subject);
    Optional<EvaluationEntity> findById(Long id);
    @Query("select count(e) from EvaluationEntity e where e.status in :statuses and e.application.deletedAt is null and e.application.archived = false")
    long countByStatusIn(@Param("statuses") List<EvaluationStatus> statuses);
    @Query("select e.status as key, count(e) as total from EvaluationEntity e where e.application.deletedAt is null and e.application.archived = false group by e.status")
    List<KeyCountView> countByStatus();
    @Query("select e.evaluationType as key, count(e) as total from EvaluationEntity e where e.application.deletedAt is null and e.application.archived = false group by e.evaluationType")
    List<KeyCountView> countByType();
    @Query("select coalesce(avg(e.score), 0) from EvaluationEntity e where e.score is not null and e.application.deletedAt is null and e.application.archived = false")
    BigDecimal averageScore();
    @Query("select e from EvaluationEntity e left join fetch e.evaluator where e.status in :statuses and e.application.deletedAt is null and e.application.archived = false order by e.createdAt desc")
    List<EvaluationEntity> findAssignments(@Param("statuses") List<EvaluationStatus> statuses);
    @Query("select e from EvaluationEntity e where e.application.id = :applicationId and e.evaluationType = 'FAMILY_INTERVIEW'")
    List<EvaluationEntity> findFamilyInterviewByApplicationId(@Param("applicationId") Long applicationId);
    Optional<EvaluationEntity> findByApplicationIdAndEvaluationType(Long applicationId, String evaluationType);

    interface KeyCountView {
        String getKey();
        long getTotal();
    }
}
