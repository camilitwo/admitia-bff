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
    @Query("select s from StudentEntity s where (:grade is null or s.gradeApplied = :grade) and (:search is null or lower(s.firstName) like lower(concat('%', :search, '%')) or lower(coalesce(s.paternalLastName,'')) like lower(concat('%', :search, '%')) or lower(coalesce(s.maternalLastName,'')) like lower(concat('%', :search, '%')) or lower(coalesce(s.rut,'')) like lower(concat('%', :search, '%')))")
    Page<StudentEntity> search(@Param("grade") String grade, @Param("search") String search, Pageable pageable);

    interface GradeCountView {
        String getGrade();
        long getTotal();
    }
}
