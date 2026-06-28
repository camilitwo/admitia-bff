package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.grade.GradeAvailabilityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GradeAvailabilityRepository extends JpaRepository<GradeAvailabilityEntity, Long> {

    Optional<GradeAvailabilityEntity> findByGradeLevel(String gradeLevel);

    List<GradeAvailabilityEntity> findByHasVacancyTrue();

    @Modifying
    @Query("UPDATE GradeAvailabilityEntity g SET g.hasVacancy = :hasVacancy, g.updatedAt = CURRENT_TIMESTAMP, g.updatedBy = :updatedBy WHERE g.gradeLevel = :gradeLevel")
    int updateVacancy(@Param("gradeLevel") String gradeLevel, @Param("hasVacancy") Boolean hasVacancy, @Param("updatedBy") String updatedBy);

    boolean existsByGradeLevelAndHasVacancyTrue(String gradeLevel);
}
