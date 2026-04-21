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
    @Query(value = "select * from guardians g where g.relationship = :relationship and (lower(g.full_name) like lower('%'||:search||'%') or lower(coalesce(g.email,'')) like lower('%'||:search||'%') or lower(coalesce(g.rut,'')) like lower('%'||:search||'%'))", nativeQuery = true)
    Page<GuardianEntity> findByRelationshipAndSearch(@Param("relationship") String relationship, @Param("search") String search, Pageable pageable);

    @Query(value = "select * from guardians g where lower(g.full_name) like lower('%'||:search||'%') or lower(coalesce(g.email,'')) like lower('%'||:search||'%') or lower(coalesce(g.rut,'')) like lower('%'||:search||'%')", nativeQuery = true)
    Page<GuardianEntity> findBySearch(@Param("search") String search, Pageable pageable);

    @Query(value = "select * from guardians g where g.relationship = :relationship", nativeQuery = true)
    Page<GuardianEntity> findByRelationship(@Param("relationship") String relationship, Pageable pageable);

    default Page<GuardianEntity> search(String relationship, String search, Pageable pageable) {
        boolean hasRel = relationship != null && !relationship.isBlank();
        boolean hasSearch = search != null && !search.isBlank();
        if (hasRel && hasSearch) return findByRelationshipAndSearch(relationship, search, pageable);
        if (hasRel) return findByRelationship(relationship, pageable);
        if (hasSearch) return findBySearch(search, pageable);
        return findAll(pageable);
    }
    long countByUserIsNotNull();
}
