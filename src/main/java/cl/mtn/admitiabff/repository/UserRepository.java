package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.user.UserEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserEntity, Long>, JpaSpecificationExecutor<UserEntity> {
    Optional<UserEntity> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    long countByActiveTrue();
    long countByRole(Role role);
    List<UserEntity> findByRoleOrderByFirstNameAscLastNameAsc(Role role);
    List<UserEntity> findByRoleInOrderByRoleAscFirstNameAscLastNameAsc(Collection<Role> roles);
    Page<UserEntity> findByRole(Role role, Pageable pageable);
    @Query("select u from UserEntity u where lower(u.firstName) like lower(concat('%', :query, '%')) or lower(u.lastName) like lower(concat('%', :query, '%')) or lower(u.email) like lower(concat('%', :query, '%')) or lower(coalesce(u.rut, '')) like lower(concat('%', :query, '%'))")
    List<UserEntity> search(@Param("query") String query, Pageable pageable);
    @Query("select u.role as role, count(u) as total from UserEntity u group by u.role")
    List<RoleCountView> countByRole();

    interface RoleCountView {
        Role getRole();
        long getTotal();
    }
}
