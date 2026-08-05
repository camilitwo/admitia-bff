package cl.mtn.admitiabff.prekinder.repository;

import cl.mtn.admitiabff.prekinder.crypto.EncryptedPayload;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderCommentRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PrekinderCommentRepository(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<StoredComment> findByOperation(UUID operationId) {
        return queryOne("""
            SELECT c.comment_id, c.evaluation_id, c.author_id, c.operation_id, c.server_sequence,
                   c.status, c.current_revision, r.revision_number, r.state, r.ciphertext, r.iv,
                   r.wrapped_dek, r.wrapped_dek_iv, r.key_version, c.created_at
              FROM comments c JOIN comment_revisions r ON r.comment_id = c.comment_id
             WHERE (c.operation_id = :operationId AND r.revision_number = c.current_revision)
                OR r.operation_id = :operationId
             ORDER BY CASE WHEN r.operation_id = :operationId THEN 0 ELSE 1 END, r.created_at DESC LIMIT 1
            """, Map.of("operationId", operationId));
    }

    public Optional<StoredComment> findCurrent(UUID commentId) {
        return queryOne("""
            SELECT c.comment_id, c.evaluation_id, c.author_id, c.operation_id, c.server_sequence,
                   c.status, c.current_revision, r.revision_number, r.state, r.ciphertext, r.iv,
                   r.wrapped_dek, r.wrapped_dek_iv, r.key_version, c.created_at
              FROM comments c JOIN comment_revisions r
                ON r.comment_id = c.comment_id AND r.revision_number = c.current_revision
             WHERE c.comment_id = :commentId FOR UPDATE OF c
            """, Map.of("commentId", commentId));
    }

    public List<StoredComment> findAll(UUID evaluationId) {
        return jdbc.query("""
            SELECT c.comment_id, c.evaluation_id, c.author_id, c.operation_id, c.server_sequence,
                   c.status, c.current_revision, r.revision_number, r.state, r.ciphertext, r.iv,
                   r.wrapped_dek, r.wrapped_dek_iv, r.key_version, c.created_at
              FROM comments c JOIN comment_revisions r
                ON r.comment_id = c.comment_id AND r.revision_number = c.current_revision
             WHERE c.evaluation_id = :evaluationId ORDER BY c.server_sequence
            """, Map.of("evaluationId", evaluationId), this::map);
    }

    public long nextSequence(UUID evaluationId) {
        Long value = jdbc.queryForObject("""
            UPDATE evaluations SET server_sequence = server_sequence + 1, updated_at = now()
             WHERE evaluation_id = :evaluationId RETURNING server_sequence
            """, Map.of("evaluationId", evaluationId), Long.class);
        if (value == null) throw new IllegalArgumentException("Evaluación Prekínder no encontrada");
        return value;
    }

    public int nextRevision(UUID commentId) {
        Integer value = jdbc.queryForObject("""
            SELECT COALESCE(MAX(revision_number), 0) + 1 FROM comment_revisions WHERE comment_id=:commentId
            """, Map.of("commentId", commentId), Integer.class);
        return value == null ? 1 : value;
    }

    public void insertComment(UUID commentId, UUID evaluationId, UUID actorId, UUID operationId,
                              long sequence, EncryptedPayload encrypted) {
        jdbc.update("""
            INSERT INTO comments(comment_id, evaluation_id, author_id, operation_id, server_sequence,
                                 status, current_revision)
            VALUES (:commentId, :evaluationId, :actorId, :operationId, :sequence, 'ACTIVE', 1)
            """, Map.of("commentId", commentId, "evaluationId", evaluationId, "actorId", actorId,
                "operationId", operationId, "sequence", sequence));
        insertRevision(UUID.randomUUID(), commentId, 1, null, "CURRENT", actorId, operationId, encrypted);
    }

    public void insertRevision(UUID revisionId, UUID commentId, int revision, Integer baseRevision,
                               String state, UUID actorId, UUID operationId, EncryptedPayload encrypted) {
        var params = new MapSqlParameterSource()
            .addValue("revisionId", revisionId).addValue("commentId", commentId)
            .addValue("revision", revision).addValue("baseRevision", baseRevision).addValue("state", state)
            .addValue("ciphertext", encrypted.ciphertext()).addValue("iv", encrypted.iv())
            .addValue("wrappedDek", encrypted.wrappedDek()).addValue("wrappedDekIv", encrypted.wrappedDekIv())
            .addValue("keyVersion", encrypted.keyVersion()).addValue("actorId", actorId)
            .addValue("operationId", operationId);
        jdbc.update("""
            INSERT INTO comment_revisions(revision_id, comment_id, revision_number, base_revision, state,
              ciphertext, iv, wrapped_dek, wrapped_dek_iv, key_version, author_id, operation_id)
            VALUES (:revisionId, :commentId, :revision, :baseRevision, :state, :ciphertext, :iv,
                    :wrappedDek, :wrappedDekIv, :keyVersion, :actorId, :operationId)
            """, params);
    }

    public void advanceCurrent(UUID commentId, int revision, String status, long sequence) {
        jdbc.update("""
            UPDATE comments SET current_revision=:revision, status=:status, server_sequence=:sequence, updated_at=now()
             WHERE comment_id=:commentId
            """, Map.of("revision", revision, "status", status, "sequence", sequence, "commentId", commentId));
    }

    public void audit(UUID actorId, String action, UUID aggregateId, String result, String requestId) {
        var params = new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID()).addValue("actor", actorId).addValue("action", action)
            .addValue("aggregate", aggregateId).addValue("result", result).addValue("requestId", requestId);
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result, request_id)
            VALUES (:id, :actor, :action, 'COMMENT', :aggregate, :result, :requestId)
            """, params);
    }

    public UUID outbox(UUID aggregateId, long sequence, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO outbox_events(event_id, aggregate_type, aggregate_id, sequence, event_type)
            VALUES (:id, 'EVALUATION', :aggregate, :sequence, :type)
            """, Map.of("id", id, "aggregate", aggregateId, "sequence", sequence, "type", type));
        return id;
    }

    public List<EventRow> eventsAfter(UUID evaluationId, long after, int limit) {
        return jdbc.query("""
            SELECT event_id, aggregate_id, sequence, event_type, created_at
              FROM outbox_events WHERE aggregate_id=:id AND sequence>:after
             ORDER BY sequence LIMIT :limit
            """, Map.of("id", evaluationId, "after", after, "limit", Math.min(limit, 500)),
            (rs, row) -> new EventRow(rs.getObject("event_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                rs.getLong("sequence"), rs.getString("event_type"), rs.getObject("created_at", OffsetDateTime.class)));
    }

    private Optional<StoredComment> queryOne(String sql, Map<String, ?> params) {
        try { return Optional.ofNullable(jdbc.queryForObject(sql, params, this::map)); }
        catch (EmptyResultDataAccessException ignored) { return Optional.empty(); }
    }

    private StoredComment map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new StoredComment(
            rs.getObject("comment_id", UUID.class), rs.getObject("evaluation_id", UUID.class),
            rs.getObject("author_id", UUID.class), rs.getObject("operation_id", UUID.class),
            rs.getLong("server_sequence"), rs.getString("status"), rs.getInt("current_revision"),
            rs.getInt("revision_number"), rs.getString("state"),
            new EncryptedPayload(rs.getString("ciphertext"), rs.getString("iv"), rs.getString("wrapped_dek"),
                rs.getString("wrapped_dek_iv"), rs.getString("key_version")),
            rs.getObject("created_at", OffsetDateTime.class));
    }

    public record StoredComment(UUID commentId, UUID evaluationId, UUID authorId, UUID operationId,
        long sequence, String status, int currentRevision, int revision, String revisionState,
        EncryptedPayload encrypted, OffsetDateTime createdAt) {}

    public record EventRow(UUID eventId, UUID entityId, long sequence, String eventType, OffsetDateTime createdAt) {}
}
