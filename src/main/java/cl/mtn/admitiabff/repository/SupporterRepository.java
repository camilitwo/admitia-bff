package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.person.SupporterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupporterRepository extends JpaRepository<SupporterEntity, Long> {
}
