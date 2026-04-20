package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, Long> {
    @EntityGraph(attributePaths = {"student", "father", "mother", "guardian", "supporter", "applicantUser"})
    @Query("select a from ApplicationEntity a where a.deletedAt is null and (:status is null or a.status = :status) and (:grade is null or a.student.gradeApplied = :grade) and (:search is null or lower(a.student.firstName) like lower(concat('%', :search, '%')) or lower(coalesce(a.student.paternalLastName,'')) like lower(concat('%', :search, '%')) or lower(coalesce(a.student.maternalLastName,'')) like lower(concat('%', :search, '%')) or lower(coalesce(a.student.rut,'')) like lower(concat('%', :search, '%')) or str(a.id) like concat('%', :search, '%'))")
    Page<ApplicationEntity> search(@Param("status") ApplicationStatus status, @Param("grade") String grade, @Param("search") String search, Pageable pageable);

    @EntityGraph(attributePaths = {"student", "father", "mother", "guardian", "supporter", "applicantUser"})
    @Query("select a from ApplicationEntity a where a.deletedAt is null and a.id = :id")
    java.util.Optional<ApplicationEntity> findActiveById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"student"})
    List<ApplicationEntity> findByDeletedAtIsNullOrderBySubmissionDateDesc(Pageable pageable);
    long countByDeletedAtIsNull();
    long countByDeletedAtIsNullAndStatus(ApplicationStatus status);
    List<ApplicationEntity> findByDeletedAtIsNullAndStatusOrderBySubmissionDateAsc(ApplicationStatus status);
    List<ApplicationEntity> findByDeletedAtIsNullAndApplicantUserIdOrderByCreatedAtDesc(Long userId);
    @Query("select distinct a from ApplicationEntity a join EvaluationEntity e on e.application = a where a.deletedAt is null and e.evaluator.id = :evaluatorId order by a.createdAt desc")
    List<ApplicationEntity> findForEvaluator(@Param("evaluatorId") Long evaluatorId);
    @Query("select a from ApplicationEntity a where a.deletedAt is null and ((:category = 'employee' and a.student.employeeChild = true) or (:category = 'alumni' and a.student.alumniChild = true) or (:category = 'inclusion' and a.student.inclusionStudent = true))")
    List<ApplicationEntity> findBySpecialCategory(@Param("category") String category);
    @Query("select a from ApplicationEntity a where a.deletedAt is null and a.createdAt between :start and :end")
    List<ApplicationEntity> findBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
