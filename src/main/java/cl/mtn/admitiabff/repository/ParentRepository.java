package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.person.ParentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParentRepository extends JpaRepository<ParentEntity, Long> {
}
