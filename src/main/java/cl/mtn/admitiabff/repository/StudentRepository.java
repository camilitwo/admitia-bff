package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.student.StudentEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentRepository extends JpaRepository<StudentEntity, Long> {
    Optional<StudentEntity> findByRut(String rut);
    List<StudentEntity> findByGradeAppliedOrderByFirstNameAscPaternalLastNameAsc(String grade);
    @Query("select s from StudentEntity s where lower(s.firstName) like lower(concat('%', :term, '%')) or lower(coalesce(s.paternalLastName,'')) like lower(concat('%', :term, '%')) or lower(coalesce(s.maternalLastName,'')) like lower(concat('%', :term, '%')) or lower(coalesce(s.rut,'')) like lower(concat('%', :term, '%'))")
    List<StudentEntity> search(@Param("term") String term, Pageable pageable);
    @Query("select s.gradeApplied as grade, count(s) as total from StudentEntity s group by s.gradeApplied order by s.gradeApplied")
    List<GradeCountView> countByGrade();
    @Query(value = "select * from students s where s.grade_applied = :grade and (lower(s.first_name) like lower('%'||:search||'%') or lower(coalesce(s.paternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.maternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.rut,'')) like lower('%'||:search||'%'))", nativeQuery = true)
    Page<StudentEntity> findByGradeAndSearch(@Param("grade") String grade, @Param("search") String search, Pageable pageable);

    @Query(value = "select * from students s where lower(s.first_name) like lower('%'||:search||'%') or lower(coalesce(s.paternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.maternal_last_name,'')) like lower('%'||:search||'%') or lower(coalesce(s.rut,'')) like lower('%'||:search||'%')", nativeQuery = true)
    Page<StudentEntity> findBySearch(@Param("search") String search, Pageable pageable);

    @Query(value = "select * from students s where s.grade_applied = :grade", nativeQuery = true)
    Page<StudentEntity> findByGrade(@Param("grade") String grade, Pageable pageable);

    default Page<StudentEntity> search(String grade, String search, Pageable pageable) {
        boolean hasGrade = grade != null && !grade.isBlank();
        boolean hasSearch = search != null && !search.isBlank();
        if (hasGrade && hasSearch) return findByGradeAndSearch(grade, search, pageable);
        if (hasGrade) return findByGrade(grade, pageable);
        if (hasSearch) return findBySearch(search, pageable);
        return findAll(pageable);
    }

    interface GradeCountView {
        String getGrade();
        long getTotal();
    }
}
