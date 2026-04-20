package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.person.GuardianEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuardianRepository extends JpaRepository<GuardianEntity, Long> {
    Optional<GuardianEntity> findByRut(String rut);
    List<GuardianEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
    @Query("select g from GuardianEntity g where (:relationship is null or g.relationship = :relationship) and (:search is null or lower(g.fullName) like lower(concat('%', :search, '%')) or lower(coalesce(g.email,'')) like lower(concat('%', :search, '%')) or lower(coalesce(g.rut,'')) like lower(concat('%', :search, '%')))")
    Page<GuardianEntity> search(@Param("relationship") String relationship, @Param("search") String search, Pageable pageable);
    long countByUserIsNotNull();
}
