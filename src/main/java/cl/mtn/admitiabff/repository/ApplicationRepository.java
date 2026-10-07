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
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, Long> {
    /** Listados admin: excluye soft-delete y archivadas; orden estable para paginación. */
    String ACTIVE_APP = "ae.deleted_at is null and ae.is_archived = false and ae.process_code = :processCode";
    String ORDER_RECENT = " order by ae.submission_date desc, ae.id desc";

    @Query(
        value = "select ae.* from applications ae join students s on s.id = ae.student_id where "
            + ACTIVE_APP
            + " and ae.status = :status and s.grade_applied = :grade and (lower(s.first_name) like lower('%'||:search||'%') or lower(coalesce(s.paternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.maternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.rut,'')) like lower('%'||:search||'%') or cast(ae.id as text) like '%'||:search||'%')"
            + ORDER_RECENT,
        nativeQuery = true)
    Page<ApplicationEntity> findByStatusAndGradeAndSearch(@Param("status") String status, @Param("grade") String grade, @Param("search") String search, @Param("processCode") String processCode, Pageable pageable);

    @Query(
        value = "select ae.* from applications ae join students s on s.id = ae.student_id where "
            + ACTIVE_APP
            + " and ae.status = :status and s.grade_applied = :grade"
            + ORDER_RECENT,
        nativeQuery = true)
    Page<ApplicationEntity> findByStatusAndGrade(@Param("status") String status, @Param("grade") String grade, @Param("processCode") String processCode, Pageable pageable);

    @Query(
        value = "select ae.* from applications ae join students s on s.id = ae.student_id where "
            + ACTIVE_APP
            + " and ae.status = :status and (lower(s.first_name) like lower('%'||:search||'%') or lower(coalesce(s.paternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.maternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.rut,'')) like lower('%'||:search||'%') or cast(ae.id as text) like '%'||:search||'%')"
            + ORDER_RECENT,
        nativeQuery = true)
    Page<ApplicationEntity> findByStatusAndSearch(@Param("status") String status, @Param("search") String search, @Param("processCode") String processCode, Pageable pageable);

    @Query(
        value = "select ae.* from applications ae join students s on s.id = ae.student_id where "
            + ACTIVE_APP
            + " and s.grade_applied = :grade and (lower(s.first_name) like lower('%'||:search||'%') or lower(coalesce(s.paternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.maternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.rut,'')) like lower('%'||:search||'%') or cast(ae.id as text) like '%'||:search||'%')"
            + ORDER_RECENT,
        nativeQuery = true)
    Page<ApplicationEntity> findByGradeAndSearch(@Param("grade") String grade, @Param("search") String search, @Param("processCode") String processCode, Pageable pageable);

    @Query(
        value = "select ae.* from applications ae where " + ACTIVE_APP + " and ae.status = :status" + ORDER_RECENT,
        nativeQuery = true)
    Page<ApplicationEntity> findByStatus(@Param("status") String status, @Param("processCode") String processCode, Pageable pageable);

    @Query(
        value = "select ae.* from applications ae join students s on s.id = ae.student_id where " + ACTIVE_APP + " and s.grade_applied = :grade" + ORDER_RECENT,
        nativeQuery = true)
    Page<ApplicationEntity> findByGrade(@Param("grade") String grade, @Param("processCode") String processCode, Pageable pageable);

    @Query(
        value = "select ae.* from applications ae join students s on s.id = ae.student_id where "
            + ACTIVE_APP
            + " and (lower(s.first_name) like lower('%'||:search||'%') or lower(coalesce(s.paternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.maternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.rut,'')) like lower('%'||:search||'%') or cast(ae.id as text) like '%'||:search||'%')"
            + ORDER_RECENT,
        nativeQuery = true)
    Page<ApplicationEntity> findBySearchOnly(@Param("search") String search, @Param("processCode") String processCode, Pageable pageable);

    @Query(value = "select ae.* from applications ae where " + ACTIVE_APP + ORDER_RECENT, nativeQuery = true)
    Page<ApplicationEntity> findAllActive(@Param("processCode") String processCode, Pageable pageable);

    default Page<ApplicationEntity> search(ApplicationStatus status, String grade, String search, String processCode, Pageable pageable) {
        boolean hasStatus = status != null;
        boolean hasGrade = grade != null && !grade.isBlank();
        boolean hasSearch = search != null && !search.isBlank();
        String st = hasStatus ? status.name() : null;
        if (hasStatus && hasGrade && hasSearch) return findByStatusAndGradeAndSearch(st, grade, search, processCode, pageable);
        if (hasStatus && hasGrade) return findByStatusAndGrade(st, grade, processCode, pageable);
        if (hasStatus && hasSearch) return findByStatusAndSearch(st, search, processCode, pageable);
        if (hasGrade && hasSearch) return findByGradeAndSearch(grade, search, processCode, pageable);
        if (hasStatus) return findByStatus(st, processCode, pageable);
        if (hasGrade) return findByGrade(grade, processCode, pageable);
        if (hasSearch) return findBySearchOnly(search, processCode, pageable);
        return findAllActive(processCode, pageable);
    }

    @EntityGraph(attributePaths = {"student", "father", "mother", "guardian", "supporter", "applicantUser"})
    @Query("select a from ApplicationEntity a where a.deletedAt is null and a.archived = false and a.id = :id")
    java.util.Optional<ApplicationEntity> findActiveById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApplicationEntity a where a.deletedAt is null and a.archived = false and a.id = :id")
    java.util.Optional<ApplicationEntity> findActiveByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {"student"})
    List<ApplicationEntity> findByDeletedAtIsNullAndArchivedFalseAndProcessCodeOrderBySubmissionDateDesc(String processCode, Pageable pageable);

    long countByDeletedAtIsNullAndArchivedFalseAndProcessCode(String processCode);

    long countByDeletedAtIsNullAndArchivedFalseAndProcessCodeAndStatus(String processCode, ApplicationStatus status);

    List<ApplicationEntity> findByDeletedAtIsNullAndArchivedFalseAndProcessCodeAndStatusOrderBySubmissionDateAsc(String processCode, ApplicationStatus status);

    @EntityGraph(attributePaths = {"student", "father", "mother", "guardian", "supporter", "applicantUser"})
    @Query("""
        select a from ApplicationEntity a
        where a.deletedAt is null
          and a.archived = false
          and (:academicYear is null or a.academicYear = :academicYear)
          and (:processCode is null or a.processCode = :processCode)
          and a.status in :statuses
        order by a.student.paternalLastName asc, a.student.maternalLastName asc, a.student.firstName asc, a.id asc
        """)
    List<ApplicationEntity> findActiveForSchoolnetExport(@Param("academicYear") Integer academicYear, @Param("processCode") String processCode, @Param("statuses") List<ApplicationStatus> statuses);

    List<ApplicationEntity> findByDeletedAtIsNullAndArchivedFalseAndProcessCodeAndApplicantUserIdOrderByCreatedAtDesc(String processCode, Long userId);

    @Query("select distinct a from ApplicationEntity a join EvaluationEntity e on e.application = a where a.deletedAt is null and a.archived = false and a.processCode = :processCode and e.evaluator.id = :evaluatorId order by a.createdAt desc")
    List<ApplicationEntity> findForEvaluator(@Param("evaluatorId") Long evaluatorId, @Param("processCode") String processCode);

    @Query("select a from ApplicationEntity a where a.deletedAt is null and a.archived = false and a.processCode = :processCode and ((:category = 'employee' and a.student.employeeChild = true) or (:category = 'alumni' and a.student.alumniChild = true) or (:category = 'inclusion' and a.student.inclusionStudent = true))")
    List<ApplicationEntity> findBySpecialCategory(@Param("category") String category, @Param("processCode") String processCode);

    @Query("select a from ApplicationEntity a where a.deletedAt is null and a.archived = false and a.processCode = :processCode and a.createdAt between :start and :end")
    List<ApplicationEntity> findBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("processCode") String processCode);

    boolean existsByStudentIdAndDeletedAtIsNull(Long studentId);

    boolean existsByStudentIdAndDeletedAtIsNullAndArchivedFalseAndProcessCode(Long studentId, String processCode);

    List<ApplicationEntity> findByFamilyIdAndAcademicYearAndProcessCodeAndDeletedAtIsNullAndArchivedFalseOrderByCreatedAtAsc(Long familyId, Integer academicYear, String processCode);

    @Query("""
        select a.status as status, count(a) as total
        from ApplicationEntity a
        where a.deletedAt is null
          and a.archived = false
          and a.academicYear = :academicYear
          and a.processCode = :processCode
        group by a.status
        """)
    List<StatusCountView> countActiveByProcess(@Param("academicYear") Integer academicYear, @Param("processCode") String processCode);

    @Query("""
        select a from ApplicationEntity a
        where a.deletedAt is null
          and a.archived = false
          and a.academicYear = :academicYear
          and a.processCode = :processCode
        """)
    List<ApplicationEntity> findActiveByProcess(@Param("academicYear") Integer academicYear, @Param("processCode") String processCode);

    interface StatusCountView {
        ApplicationStatus getStatus();
        long getTotal();
    }
}
