package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.interview.InterviewerPairEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewerPairRepository extends JpaRepository<InterviewerPairEntity, Long> {
    List<InterviewerPairEntity> findAllByOrderByActiveDescCreatedAtDesc();

    List<InterviewerPairEntity> findByActiveTrueOrderByCycleDirectorFirstNameAscCycleDirectorLastNameAsc();

    @Query("select distinct p from InterviewerPairEntity p join p.grades grade where p.active = true and grade = :grade")
    List<InterviewerPairEntity> findActiveByGrade(@Param("grade") String grade);

    @Query("select p from InterviewerPairEntity p where p.active = true and (p.cycleDirector.id = :userId or p.psychologist.id = :userId)")
    Optional<InterviewerPairEntity> findActiveByMember(@Param("userId") Long userId);

    @Query("select count(p) from InterviewerPairEntity p where p.cycleDirector.id = :userId or p.psychologist.id = :userId")
    long countByMember(@Param("userId") Long userId);
}
