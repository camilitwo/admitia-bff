package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.application.AdmissionCycleEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdmissionCycleRepository extends JpaRepository<AdmissionCycleEntity, Long> {
    Optional<AdmissionCycleEntity> findByAcademicYear(Integer academicYear);

    Optional<AdmissionCycleEntity> findFirstByOrderByAcademicYearDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AdmissionCycleEntity c where c.academicYear = :academicYear")
    Optional<AdmissionCycleEntity> findByAcademicYearForUpdate(@Param("academicYear") Integer academicYear);
}
