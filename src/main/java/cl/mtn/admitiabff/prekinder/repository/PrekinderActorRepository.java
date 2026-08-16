package cl.mtn.admitiabff.prekinder.repository;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import java.util.Map;
import java.util.List;
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

    public PrekinderActor upsertSubject(String subject, String emailHash, String proposedRole) {
        List<PrekinderActor> linked = jdbc.query("""
            UPDATE actors SET external_subject = :subject, updated_at = now()
             WHERE actor_id = (
                SELECT p.professional_id FROM professional_profiles p JOIN actors a ON a.actor_id = p.professional_id
                 WHERE a.email_hash = :emailHash AND p.active = true
                   AND (a.external_subject IS NULL OR a.external_subject LIKE 'legacy:%')
                 LIMIT 1
             )
            RETURNING actor_id, legacy_user_id, role_code
            """, Map.of("subject", subject, "emailHash", emailHash),
            (rs, row) -> new PrekinderActor(rs.getObject("actor_id", UUID.class), rs.getLong("legacy_user_id"), rs.getString("role_code")));
        if (!linked.isEmpty()) return linked.getFirst();
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("""
            INSERT INTO actors(actor_id, external_subject, email_hash, role_code)
            VALUES (:id, :subject, :emailHash, :role)
            ON CONFLICT (external_subject) WHERE external_subject IS NOT NULL DO UPDATE
              SET email_hash = EXCLUDED.email_hash,
                  role_code = CASE
                      WHEN EXCLUDED.role_code IN ('APODERADO', 'PREKINDER_PROFESSIONAL') THEN actors.role_code
                      ELSE EXCLUDED.role_code
                  END,
                  updated_at = now()
            RETURNING actor_id, legacy_user_id, role_code
            """, Map.of("id", id, "subject", subject, "emailHash", emailHash, "role", proposedRole),
            (rs, row) -> new PrekinderActor(rs.getObject("actor_id", UUID.class), rs.getLong("legacy_user_id"), rs.getString("role_code")));
    }
}
