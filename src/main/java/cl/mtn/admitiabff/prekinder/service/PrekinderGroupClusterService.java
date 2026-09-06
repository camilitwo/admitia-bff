package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.realtime.PrekinderRealtimeNotifier;
import cl.mtn.admitiabff.prekinder.service.PrekinderFlowService.GroupView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderGroupClusterService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final PrekinderFlowService flow;
    private final PrekinderRealtimeNotifier realtime;

    public PrekinderGroupClusterService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
        PrekinderAccessService access, PrekinderFlowService flow, PrekinderRealtimeNotifier realtime) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.flow = flow;
        this.realtime = realtime;
    }

    public List<ClusterView> clusters(UUID processId, LocalDate date) {
        access.requireAdmin();
        List<UUID> ids = jdbc.query("""
            SELECT c.cluster_id FROM evaluation_group_clusters c
              JOIN evaluation_days d ON d.day_id = c.evaluation_day_id
             WHERE c.process_id = :processId AND d.day_date = :date
             ORDER BY c.created_at
            """, Map.of("processId", processId, "date", date), (rs, row) -> rs.getObject("cluster_id", UUID.class));
        return ids.stream().map(this::clusterView).toList();
    }

    public ClusterView createCluster(UUID processId, UUID evaluationDayId, String name, List<UUID> groupIds) {
        PrekinderActor actor = access.requireAdmin();
        List<UUID> ids = groupIds == null ? List.of() : groupIds.stream().distinct().toList();
        if (ids.size() < 2) {
            throw PrekinderDomainException.conflict("CLUSTER_MIN_GROUPS", "Una agrupación necesita al menos 2 grupos");
        }
        return transactions.execute(status -> {
            ensureGroupsBelongToDay(ids, processId, evaluationDayId);
            Instant[] window = deriveWindow(ids);
            ensureNoSharedGroupOverlap(null, ids, window[0], window[1]);
            UUID clusterId = UUID.randomUUID();
            try {
                jdbc.update("""
                    INSERT INTO evaluation_group_clusters(cluster_id, process_id, evaluation_day_id, name, created_by)
                    VALUES (:id, :processId, :dayId, :name, :actorId)
                    """, Map.of("id", clusterId, "processId", processId, "dayId", evaluationDayId,
                    "name", clean(name), "actorId", actor.id()));
            } catch (DataIntegrityViolationException exception) {
                throw PrekinderDomainException.conflict("CLUSTER_NAME_TAKEN",
                    "Ya existe una agrupación con ese nombre en esta jornada");
            }
            insertMembers(clusterId, ids);
            audit(actor.id(), "GROUP_CLUSTER_CREATED", clusterId, Map.of("groupCount", ids.size()));
            realtime.notifyAfterCommit(actor.id(), processId, "GROUP_CLUSTER_CREATED");
            return clusterView(clusterId);
        });
    }

    public ClusterView updateCluster(UUID clusterId, String name, List<UUID> groupIds, String reason, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        List<UUID> ids = groupIds == null ? List.of() : groupIds.stream().distinct().toList();
        if (ids.size() < 2) {
            throw PrekinderDomainException.conflict("CLUSTER_MIN_GROUPS", "Una agrupación necesita al menos 2 grupos");
        }
        return transactions.execute(status -> {
            ClusterHeader current = header(clusterId);
            if (current.version() != expectedVersion) throw new VersionConflictException("La agrupación cambió");
            if (!"ACTIVE".equals(current.status())) {
                throw PrekinderDomainException.conflict("CLUSTER_NOT_EDITABLE", "Esta agrupación ya no se puede modificar");
            }
            ensureGroupsBelongToDay(ids, current.processId(), current.evaluationDayId());
            Instant[] window = deriveWindow(ids);
            ensureNoSharedGroupOverlap(clusterId, ids, window[0], window[1]);

            List<UUID> currentMembers = jdbc.queryForList("""
                SELECT group_id FROM evaluation_group_cluster_members WHERE cluster_id = :id
                """, Map.of("id", clusterId), UUID.class);
            for (UUID toRemove : currentMembers.stream().filter(id -> !ids.contains(id)).toList()) {
                jdbc.update("DELETE FROM evaluation_group_cluster_members WHERE cluster_id = :c AND group_id = :g",
                    Map.of("c", clusterId, "g", toRemove));
            }
            int position = 0;
            for (UUID groupId : ids) {
                if (currentMembers.contains(groupId)) {
                    jdbc.update("""
                        UPDATE evaluation_group_cluster_members SET position = :p WHERE cluster_id = :c AND group_id = :g
                        """, Map.of("p", position, "c", clusterId, "g", groupId));
                } else {
                    jdbc.update("""
                        INSERT INTO evaluation_group_cluster_members(cluster_id, group_id, position) VALUES (:c, :g, :p)
                        """, Map.of("c", clusterId, "g", groupId, "p", position));
                }
                position++;
            }
            try {
                int updated = jdbc.update("""
                    UPDATE evaluation_group_clusters SET name = :name, version = version + 1, updated_at = now()
                     WHERE cluster_id = :id AND version = :version
                    """, Map.of("name", clean(name), "id", clusterId, "version", expectedVersion));
                if (updated != 1) throw new VersionConflictException("La agrupación cambió");
            } catch (DataIntegrityViolationException exception) {
                throw PrekinderDomainException.conflict("CLUSTER_NAME_TAKEN",
                    "Ya existe una agrupación con ese nombre en esta jornada");
            }
            audit(actor.id(), "GROUP_CLUSTER_UPDATED", clusterId, Map.of("reason", reason == null ? "" : reason));
            realtime.notifyAfterCommit(actor.id(), current.processId(), "GROUP_CLUSTER_UPDATED");
            return clusterView(clusterId);
        });
    }

    public ClusterView deleteCluster(UUID clusterId, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            ClusterHeader current = header(clusterId);
            if (current.version() != expectedVersion) throw new VersionConflictException("La agrupación cambió");
            int updated = jdbc.update("""
                UPDATE evaluation_group_clusters SET status = 'CANCELLED', version = version + 1, updated_at = now()
                 WHERE cluster_id = :id AND version = :version AND status = 'ACTIVE'
                """, Map.of("id", clusterId, "version", expectedVersion));
            if (updated != 1) throw new VersionConflictException("La agrupación cambió");
            audit(actor.id(), "GROUP_CLUSTER_DELETED", clusterId, Map.of());
            realtime.notifyAfterCommit(actor.id(), current.processId(), "GROUP_CLUSTER_DELETED");
            return clusterView(clusterId);
        });
    }

    public ClusterView addGroup(UUID clusterId, UUID groupId, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            ClusterHeader current = header(clusterId);
            if (current.version() != expectedVersion) throw new VersionConflictException("La agrupación cambió");
            if (!"ACTIVE".equals(current.status())) {
                throw PrekinderDomainException.conflict("CLUSTER_NOT_EDITABLE", "Esta agrupación ya no se puede modificar");
            }
            ensureGroupsBelongToDay(List.of(groupId), current.processId(), current.evaluationDayId());
            List<UUID> members = jdbc.queryForList("""
                SELECT group_id FROM evaluation_group_cluster_members WHERE cluster_id = :id
                """, Map.of("id", clusterId), UUID.class);
            if (!members.contains(groupId)) {
                List<UUID> resulting = new ArrayList<>(members);
                resulting.add(groupId);
                Instant[] window = deriveWindow(resulting);
                ensureNoSharedGroupOverlap(clusterId, resulting, window[0], window[1]);
                jdbc.update("""
                    INSERT INTO evaluation_group_cluster_members(cluster_id, group_id, position) VALUES (:c, :g, :p)
                    """, Map.of("c", clusterId, "g", groupId, "p", members.size()));
                bumpClusterVersion(clusterId, expectedVersion);
                audit(actor.id(), "GROUP_CLUSTER_UPDATED", clusterId, Map.of("groupAdded", groupId.toString()));
                realtime.notifyAfterCommit(actor.id(), current.processId(), "GROUP_CLUSTER_UPDATED");
            }
            return clusterView(clusterId);
        });
    }

    public ClusterView removeGroup(UUID clusterId, UUID groupId, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            ClusterHeader current = header(clusterId);
            if (current.version() != expectedVersion) throw new VersionConflictException("La agrupación cambió");
            int deleted = jdbc.update("""
                DELETE FROM evaluation_group_cluster_members WHERE cluster_id = :c AND group_id = :g
                """, Map.of("c", clusterId, "g", groupId));
            if (deleted == 0) {
                throw PrekinderDomainException.conflict("MEMBER_NOT_ASSIGNED", "El grupo no pertenece a la agrupación");
            }
            bumpClusterVersion(clusterId, expectedVersion);
            audit(actor.id(), "GROUP_CLUSTER_UPDATED", clusterId, Map.of("groupRemoved", groupId.toString()));
            realtime.notifyAfterCommit(actor.id(), current.processId(), "GROUP_CLUSTER_UPDATED");
            return clusterView(clusterId);
        });
    }

    public ClusterView rescheduleCluster(UUID clusterId, Instant startsAt, Integer durationMinutes, String reason,
                                         long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        if (startsAt == null) throw new IllegalArgumentException("La nueva hora es obligatoria");
        int duration = durationMinutes == null ? 30 : durationMinutes;
        if (duration < 10 || duration > 240) throw new IllegalArgumentException("Duración fuera de rango");
        return transactions.execute(status -> {
            ClusterHeader current = header(clusterId);
            if (current.version() != expectedVersion) throw new VersionConflictException("La agrupación cambió");
            if (!"ACTIVE".equals(current.status())) {
                throw PrekinderDomainException.conflict("CLUSTER_NOT_EDITABLE", "Esta agrupación ya no se puede modificar");
            }
            List<UUID> memberIds = jdbc.queryForList("""
                SELECT group_id FROM evaluation_group_cluster_members WHERE cluster_id = :id ORDER BY position
                """, Map.of("id", clusterId), UUID.class);
            if (memberIds.isEmpty()) {
                throw PrekinderDomainException.conflict("CLUSTER_MIN_GROUPS", "La agrupación no tiene grupos");
            }
            Instant endsAt = startsAt.plus(Duration.ofMinutes(duration));
            ensureNoSharedGroupOverlap(clusterId, memberIds, startsAt, endsAt);

            List<UUID> conflicting = new ArrayList<>();
            for (UUID groupId : memberIds) {
                GroupView group = flow.group(groupId);
                if ("CANCELLED".equals(group.status())) continue;
                try {
                    flow.rescheduleGroup(groupId, group.roomId(), startsAt, duration, reason, group.version());
                } catch (VersionConflictException | PrekinderDomainException exception) {
                    conflicting.add(groupId);
                }
            }
            if (!conflicting.isEmpty()) {
                throw PrekinderDomainException.conflict("CLUSTER_SCHEDULE_CONFLICT",
                    "El nuevo horario cruza con otro bloque", Map.of("conflictingGroupIds", conflicting));
            }
            bumpClusterVersion(clusterId, expectedVersion);
            audit(actor.id(), "GROUP_CLUSTER_RESCHEDULED", clusterId, Map.of("reason", reason == null ? "" : reason));
            realtime.notifyAfterCommit(actor.id(), current.processId(), "GROUP_CLUSTER_RESCHEDULED");
            return clusterView(clusterId);
        });
    }

    public ClusterView confirmCluster(UUID clusterId, long expectedVersion) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            ClusterHeader current = header(clusterId);
            if (current.version() != expectedVersion) throw new VersionConflictException("La agrupación cambió");
            List<UUID> memberIds = jdbc.queryForList("""
                SELECT group_id FROM evaluation_group_cluster_members WHERE cluster_id = :id
                """, Map.of("id", clusterId), UUID.class);
            List<UUID> failing = new ArrayList<>();
            for (UUID groupId : memberIds) {
                GroupView group = flow.group(groupId);
                if (!"DRAFT".equals(group.status())) continue;
                try {
                    flow.confirmGroup(groupId, group.version());
                } catch (VersionConflictException | PrekinderDomainException exception) {
                    failing.add(groupId);
                }
            }
            if (!failing.isEmpty()) {
                throw PrekinderDomainException.conflict("CLUSTER_NOT_READY",
                    "Faltan integrantes, evaluadores o rúbrica en algunos grupos", Map.of("conflictingGroupIds", failing));
            }
            bumpClusterVersion(clusterId, expectedVersion);
            audit(actor.id(), "GROUP_CLUSTER_UPDATED", clusterId, Map.of("action", "CONFIRMED"));
            realtime.notifyAfterCommit(actor.id(), current.processId(), "GROUP_CLUSTER_UPDATED");
            return clusterView(clusterId);
        });
    }

    private ClusterView clusterView(UUID clusterId) {
        ClusterHeader header = header(clusterId);
        List<UUID> groupIds = jdbc.queryForList("""
            SELECT group_id FROM evaluation_group_cluster_members WHERE cluster_id = :id ORDER BY position, added_at
            """, Map.of("id", clusterId), UUID.class);
        List<GroupView> groups = groupIds.stream().map(flow::group).toList();
        List<GroupView> active = groups.stream().filter(group -> !"CANCELLED".equals(group.status())).toList();
        List<ClusterGroupSummary> summaries = groups.stream()
            .map(group -> new ClusterGroupSummary(group.groupId(), group.code(), group.roomId(), group.roomName(),
                group.stage(), group.startsAt(), group.endsAt(), group.status(), group.capacity(),
                group.memberIds().size(), group.requiredEvaluators(), group.evaluatorIds().size()))
            .toList();
        String status = "CANCELLED".equals(header.status()) ? "CANCELLED" : deriveStatus(active);
        Instant startsAt = active.stream().map(GroupView::startsAt).min(Instant::compareTo).orElse(null);
        Instant endsAt = active.stream().map(GroupView::endsAt).max(Instant::compareTo).orElse(null);
        int memberCount = active.stream().mapToInt(group -> group.memberIds().size()).sum();
        int evaluatorCount = active.stream().mapToInt(group -> group.evaluatorIds().size()).sum();
        LocalDate date = jdbc.queryForObject("SELECT day_date FROM evaluation_days WHERE day_id = :id",
            Map.of("id", header.evaluationDayId()), LocalDate.class);
        return new ClusterView(header.clusterId(), header.processId(), header.evaluationDayId(), date, header.name(),
            status, header.version(), groupIds, groupIds.size(), memberCount, evaluatorCount, startsAt, endsAt, summaries);
    }

    private static String deriveStatus(List<GroupView> active) {
        if (active.isEmpty()) return "DRAFT";
        if (active.stream().anyMatch(group -> "DRAFT".equals(group.status()))) return "DRAFT";
        if (active.stream().allMatch(group -> "COMPLETED".equals(group.status()))) return "COMPLETED";
        return "CONFIRMED";
    }

    private ClusterHeader header(UUID clusterId) {
        return jdbc.queryForObject("""
            SELECT cluster_id, process_id, evaluation_day_id, name, status, version
              FROM evaluation_group_clusters WHERE cluster_id = :id
            """, Map.of("id", clusterId), (rs, row) -> new ClusterHeader(rs.getObject("cluster_id", UUID.class),
                rs.getObject("process_id", UUID.class), rs.getObject("evaluation_day_id", UUID.class),
                rs.getString("name"), rs.getString("status"), rs.getLong("version")));
    }

    private void ensureGroupsBelongToDay(List<UUID> groupIds, UUID processId, UUID evaluationDayId) {
        Long matching = jdbc.queryForObject("""
            SELECT count(*) FROM evaluation_groups
             WHERE group_id IN (:ids) AND process_id = :processId AND day_id = :dayId
            """, Map.of("ids", groupIds, "processId", processId, "dayId", evaluationDayId), Long.class);
        if (matching == null || matching != groupIds.size()) {
            throw PrekinderDomainException.conflict("CLUSTER_GROUPS_DIFFERENT_DAY",
                "Solo puedes agrupar grupos de la misma jornada");
        }
    }

    private Instant[] deriveWindow(List<UUID> groupIds) {
        return jdbc.queryForObject("""
            SELECT MIN(starts_at) AS window_start, MAX(ends_at) AS window_end FROM evaluation_groups
             WHERE group_id IN (:ids) AND status <> 'CANCELLED'
            """, Map.of("ids", groupIds), (rs, row) -> {
                Timestamp start = rs.getTimestamp("window_start");
                Timestamp end = rs.getTimestamp("window_end");
                return new Instant[]{start == null ? null : start.toInstant(), end == null ? null : end.toInstant()};
            });
    }

    private void ensureNoSharedGroupOverlap(UUID excludeClusterId, List<UUID> groupIds, Instant windowStart, Instant windowEnd) {
        if (groupIds.isEmpty() || windowStart == null || windowEnd == null) return;
        List<Object[]> rows = jdbc.query("""
            SELECT c2.cluster_id AS cluster_id, MIN(g.starts_at) AS win_start, MAX(g.ends_at) AS win_end
              FROM evaluation_group_cluster_members m2
              JOIN evaluation_group_clusters c2 ON c2.cluster_id = m2.cluster_id
              JOIN evaluation_group_cluster_members allm ON allm.cluster_id = c2.cluster_id
              JOIN evaluation_groups g ON g.group_id = allm.group_id AND g.status <> 'CANCELLED'
             WHERE m2.group_id IN (:groupIds) AND c2.status = 'ACTIVE'
               AND (CAST(:excludeId AS uuid) IS NULL OR c2.cluster_id <> CAST(:excludeId AS uuid))
             GROUP BY c2.cluster_id
            """, new MapSqlParameterSource().addValue("groupIds", groupIds).addValue("excludeId", excludeClusterId),
            (rs, row) -> new Object[]{rs.getObject("cluster_id", UUID.class), rs.getTimestamp("win_start").toInstant(),
                rs.getTimestamp("win_end").toInstant()});
        for (Object[] row : rows) {
            Instant otherStart = (Instant) row[1];
            Instant otherEnd = (Instant) row[2];
            if (windowStart.isBefore(otherEnd) && otherStart.isBefore(windowEnd)) {
                throw PrekinderDomainException.conflict("CLUSTER_GROUP_SCHEDULE_OVERLAP",
                    "Un grupo ya pertenece a otra agrupación con un horario que se cruza",
                    Map.of("conflictingClusterId", row[0]));
            }
        }
    }

    private void insertMembers(UUID clusterId, List<UUID> groupIds) {
        int position = 0;
        for (UUID groupId : groupIds) {
            jdbc.update("""
                INSERT INTO evaluation_group_cluster_members(cluster_id, group_id, position) VALUES (:c, :g, :p)
                """, Map.of("c", clusterId, "g", groupId, "p", position));
            position++;
        }
    }

    private void bumpClusterVersion(UUID clusterId, long expectedVersion) {
        int updated = jdbc.update("""
            UPDATE evaluation_group_clusters SET version = version + 1, updated_at = now()
             WHERE cluster_id = :id AND version = :version
            """, Map.of("id", clusterId, "version", expectedVersion));
        if (updated != 1) throw new VersionConflictException("La agrupación cambió");
    }

    private void audit(UUID actorId, String action, UUID aggregateId, Map<String, ?> metadata) {
        String json;
        try { json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata); }
        catch (Exception ignored) { json = "{}"; }
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result, metadata)
            VALUES (:id, :actorId, :action, 'GROUP_CLUSTER', :aggregateId, 'SUCCESS', CAST(:metadata AS jsonb))
            """, Map.of("id", UUID.randomUUID(), "actorId", actorId, "action", action,
                "aggregateId", aggregateId, "metadata", json));
    }

    private static String clean(String value) { return value == null ? null : value.trim(); }

    private record ClusterHeader(UUID clusterId, UUID processId, UUID evaluationDayId, String name, String status,
                                 long version) {}

    public record ClusterGroupSummary(UUID groupId, String code, UUID roomId, String roomName, String stage,
        Instant startsAt, Instant endsAt, String status, int capacity, int memberCount,
        int requiredEvaluators, int evaluatorCount) {}

    public record ClusterView(UUID clusterId, UUID processId, UUID evaluationDayId, LocalDate date, String name,
        String status, long version, List<UUID> groupIds, int groupCount, int memberCount, int evaluatorCount,
        Instant startsAt, Instant endsAt, List<ClusterGroupSummary> groups) {}
}
