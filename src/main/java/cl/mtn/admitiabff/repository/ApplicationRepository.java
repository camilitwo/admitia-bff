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
    @Query(value = "select ae.* from applications ae join students s on s.id = ae.student_id where ae.deleted_at is null and ae.status = :status and s.grade_applied = :grade and (lower(s.first_name) like lower('%'||:search||'%') or lower(coalesce(s.paternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.maternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.rut,'')) like lower('%'||:search||'%') or cast(ae.id as text) like '%'||:search||'%')", nativeQuery = true)
    Page<ApplicationEntity> findByStatusAndGradeAndSearch(@Param("status") String status, @Param("grade") String grade, @Param("search") String search, Pageable pageable);

    @Query(value = "select ae.* from applications ae join students s on s.id = ae.student_id where ae.deleted_at is null and ae.status = :status and s.grade_applied = :grade", nativeQuery = true)
    Page<ApplicationEntity> findByStatusAndGrade(@Param("status") String status, @Param("grade") String grade, Pageable pageable);

    @Query(value = "select ae.* from applications ae join students s on s.id = ae.student_id where ae.deleted_at is null and ae.status = :status and (lower(s.first_name) like lower('%'||:search||'%') or lower(coalesce(s.paternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.maternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.rut,'')) like lower('%'||:search||'%') or cast(ae.id as text) like '%'||:search||'%')", nativeQuery = true)
    Page<ApplicationEntity> findByStatusAndSearch(@Param("status") String status, @Param("search") String search, Pageable pageable);

    @Query(value = "select ae.* from applications ae join students s on s.id = ae.student_id where ae.deleted_at is null and s.grade_applied = :grade and (lower(s.first_name) like lower('%'||:search||'%') or lower(coalesce(s.paternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.maternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.rut,'')) like lower('%'||:search||'%') or cast(ae.id as text) like '%'||:search||'%')", nativeQuery = true)
    Page<ApplicationEntity> findByGradeAndSearch(@Param("grade") String grade, @Param("search") String search, Pageable pageable);

    @Query(value = "select ae.* from applications ae where ae.deleted_at is null and ae.status = :status", nativeQuery = true)
    Page<ApplicationEntity> findByStatus(@Param("status") String status, Pageable pageable);

    @Query(value = "select ae.* from applications ae join students s on s.id = ae.student_id where ae.deleted_at is null and s.grade_applied = :grade", nativeQuery = true)
    Page<ApplicationEntity> findByGrade(@Param("grade") String grade, Pageable pageable);

    @Query(value = "select ae.* from applications ae join students s on s.id = ae.student_id where ae.deleted_at is null and (lower(s.first_name) like lower('%'||:search||'%') or lower(coalesce(s.paternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.maternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.rut,'')) like lower('%'||:search||'%') or cast(ae.id as text) like '%'||:search||'%')", nativeQuery = true)
    Page<ApplicationEntity> findBySearchOnly(@Param("search") String search, Pageable pageable);

    @Query(value = "select ae.* from applications ae where ae.deleted_at is null", nativeQuery = true)
    Page<ApplicationEntity> findAllActive(Pageable pageable);

    default Page<ApplicationEntity> search(ApplicationStatus status, String grade, String search, Pageable pageable) {
        boolean hasStatus = status != null;
        boolean hasGrade = grade != null && !grade.isBlank();
        boolean hasSearch = search != null && !search.isBlank();
        String st = hasStatus ? status.name() : null;
        if (hasStatus && hasGrade && hasSearch) return findByStatusAndGradeAndSearch(st, grade, search, pageable);
        if (hasStatus && hasGrade)              return findByStatusAndGrade(st, grade, pageable);
        if (hasStatus && hasSearch)             return findByStatusAndSearch(st, search, pageable);
        if (hasGrade && hasSearch)              return findByGradeAndSearch(grade, search, pageable);
        if (hasStatus)                          return findByStatus(st, pageable);
        if (hasGrade)                           return findByGrade(grade, pageable);
        if (hasSearch)                          return findBySearchOnly(search, pageable);
        return findAllActive(pageable);
    }

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
