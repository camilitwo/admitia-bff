package cl.mtn.admitiabff.prekinder.repository;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderActorRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PrekinderActorRepository(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PrekinderActor upsert(long legacyUserId, String role) {
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("""
            INSERT INTO actors(actor_id, legacy_user_id, role_code)
            VALUES (:id, :legacyUserId, :role)
            ON CONFLICT (legacy_user_id) DO UPDATE
              SET role_code = EXCLUDED.role_code, updated_at = now()
            RETURNING actor_id, legacy_user_id, role_code
            """, Map.of("id", id, "legacyUserId", legacyUserId, "role", role),
            (rs, row) -> new PrekinderActor(rs.getObject("actor_id", UUID.class), rs.getLong("legacy_user_id"), rs.getString("role_code")));
    }
}
