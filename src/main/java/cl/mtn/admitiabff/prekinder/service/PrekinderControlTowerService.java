package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderControlTowerService {
    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;

    public PrekinderControlTowerService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
        @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
        PrekinderAccessService access) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
    }

    public ControlTowerDay controlTower(UUID processId, LocalDate date) {
        access.requireOperations();
        LocalDate effectiveDate = date == null ? LocalDate.now(SANTIAGO) : date;
        List<GroupRow> groups = jdbc.query("""
            SELECT g.group_id, g.room_id, room.name AS room_name, g.code, g.starts_at, g.ends_at,
                   g.status, g.capacity, g.version,
                   count(m.member_id) FILTER (WHERE m.status IN ('ASSIGNED','ATTENDED','ABSENT')) AS member_count,
                   count(m.member_id) FILTER (WHERE m.status = 'ATTENDED') AS present_count,
                   count(m.member_id) FILTER (WHERE m.status = 'ASSIGNED') AS pending_count,
                   count(m.member_id) FILTER (WHERE m.status = 'ABSENT') AS absent_count
              FROM evaluation_groups g
              JOIN prekinder_rooms room ON room.room_id = g.room_id
              LEFT JOIN evaluation_group_members m ON m.group_id = g.group_id
             WHERE g.process_id = :processId
               AND g.starts_at >= :from AND g.starts_at < :to
             GROUP BY g.group_id, room.name
             ORDER BY room.name, g.starts_at, g.code
            """, window(processId, effectiveDate), (rs, row) -> new GroupRow(
                rs.getObject("group_id", UUID.class), rs.getObject("room_id", UUID.class), rs.getString("room_name"),
                rs.getString("code"), rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                rs.getString("status"), rs.getInt("capacity"), rs.getLong("version"), rs.getInt("member_count"),
                rs.getInt("present_count"), rs.getInt("pending_count"), rs.getInt("absent_count")));

        Map<UUID, Map<String, String>> progress = new LinkedHashMap<>();
        List<Map<String, Object>> progressRows = jdbc.queryForList("""
            SELECT ia.group_id, ia.instrument_code,
                   CASE
                     WHEN bool_and(r.status IN ('VALIDATED','LOCKED')) AND count(r.report_id) > 0 THEN 'VALIDATED'
                     WHEN bool_and(r.status IN ('COMPLETED','SUBMITTED','VALIDATED','LOCKED')) AND count(r.report_id) > 0 THEN 'SUBMITTED'
                     WHEN bool_or(r.status = 'IN_PROGRESS') THEN 'IN_PROGRESS'
                     ELSE 'PENDING'
                   END AS progress
              FROM group_instrument_assignments ia
              LEFT JOIN evaluator_reports r ON r.instrument_assignment_id = ia.assignment_id
             WHERE ia.group_id IN (:groupIds) AND ia.status NOT IN ('REPLACED','CANCELLED')
             GROUP BY ia.group_id, ia.instrument_code
            """, Map.of("groupIds", groups.isEmpty() ? List.of(new UUID(0, 0)) : groups.stream().map(GroupRow::groupId).toList()));
        for (Map<String, Object> row : progressRows) {
            UUID groupId = (UUID) row.get("group_id");
            progress.computeIfAbsent(groupId, ignored -> new LinkedHashMap<>())
                .put(String.valueOf(row.get("instrument_code")), String.valueOf(row.get("progress")));
        }

        Map<UUID, RoomBuilder> rooms = new LinkedHashMap<>();
        for (GroupRow group : groups) {
            rooms.computeIfAbsent(group.roomId(), ignored -> new RoomBuilder(group.roomId(), group.roomName()))
                .groups.add(new ControlTowerGroup(group.groupId(), group.code(), group.startsAt(), group.endsAt(),
                    operationalStatus(group), group.capacity(), group.memberCount(),
                    new Attendance(group.present(), group.pending(), group.absent()),
                    progress.getOrDefault(group.groupId(), Map.of()), group.version()));
        }
        int openIncidents = jdbc.queryForObject("""
            SELECT count(*) FROM prekinder_operational_incidents
             WHERE process_id = :processId AND status = 'OPEN'
               AND reported_at >= :from AND reported_at < :to
            """, window(processId, effectiveDate), Integer.class);
        long sequence = jdbc.queryForObject("SELECT coalesce(max(sequence), 0) FROM outbox_events", Map.of(), Long.class);
        Summary summary = new Summary(groups.stream().mapToInt(GroupRow::memberCount).sum(),
            groups.stream().mapToInt(GroupRow::present).sum(),
            (int) groups.stream().filter(group -> "IN_PROGRESS".equals(group.status())).count(),
            (int) groups.stream().filter(group -> "COMPLETED".equals(group.status())).count(), openIncidents);
        return new ControlTowerDay(processId, effectiveDate, SANTIAGO.getId(), sequence, summary,
            rooms.values().stream().map(RoomBuilder::view).toList());
    }

    public AttendanceUpdate updateAttendance(UUID groupId, UUID applicationId, String requestedStatus,
                                             String reasonCode, long expectedVersion, UUID operationId) {
        PrekinderActor actor = access.requireOperations();
        AttendanceState state = AttendanceState.from(requestedStatus);
        return transactions.execute(transaction -> {
            List<UUID> duplicate = jdbc.queryForList("""
                SELECT result_reference FROM processed_operations WHERE operation_id = :operationId
                """, Map.of("operationId", operationId), UUID.class);
            if (!duplicate.isEmpty()) return attendance(groupId, applicationId);
            int updated = jdbc.update("""
                UPDATE evaluation_group_members
                   SET status = :status, attendance_detail = :detail,
                       attendance_reason_code = :reasonCode, attendance_recorded_by = :actorId,
                       attendance_recorded_at = now(), version = version + 1, updated_at = now()
                 WHERE group_id = :groupId AND application_id = :applicationId
                   AND version = :expectedVersion AND status NOT IN ('MOVED','CANCELLED')
                """, new MapSqlParameterSource().addValue("status", state.persisted()).addValue("detail", state.detail())
                .addValue("reasonCode", reasonCode).addValue("actorId", actor.id()).addValue("groupId", groupId)
                .addValue("applicationId", applicationId).addValue("expectedVersion", expectedVersion));
            if (updated != 1) throw new VersionConflictException("La asistencia cambió");
            jdbc.update("""
                INSERT INTO processed_operations(operation_id, actor_id, operation_type, result_reference)
                VALUES (:operationId, :actorId, 'MEMBER_ATTENDANCE_CHANGED', :applicationId)
                """, Map.of("operationId", operationId, "actorId", actor.id(), "applicationId", applicationId));
            audit(actor.id(), "MEMBER_ATTENDANCE_CHANGED", applicationId,
                Map.of("groupId", groupId.toString(), "status", state.presented()));
            return attendance(groupId, applicationId);
        });
    }

    private AttendanceUpdate attendance(UUID groupId, UUID applicationId) {
        return jdbc.queryForObject("""
            SELECT group_id, application_id, status, attendance_detail, attendance_reason_code, version,
                   attendance_recorded_at
              FROM evaluation_group_members WHERE group_id = :groupId AND application_id = :applicationId
            """, Map.of("groupId", groupId, "applicationId", applicationId), (rs, row) ->
            new AttendanceUpdate(groupId, applicationId,
                AttendanceState.presented(rs.getString("status"), rs.getString("attendance_detail")),
                rs.getString("attendance_reason_code"), rs.getLong("version"),
                rs.getTimestamp("attendance_recorded_at") == null ? null : rs.getTimestamp("attendance_recorded_at").toInstant()));
    }

    private MapSqlParameterSource window(UUID processId, LocalDate date) {
        Instant from = date.atStartOfDay(SANTIAGO).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(SANTIAGO).toInstant();
        return new MapSqlParameterSource().addValue("processId", processId)
            .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
    }

    private static String operationalStatus(GroupRow group) {
        if ("COMPLETED".equals(group.status())) return "COMPLETED";
        if ("IN_PROGRESS".equals(group.status())) return "IN_PROGRESS";
        if (group.present() + group.absent() > 0) return "RECEPTION";
        return group.status();
    }

    private void audit(UUID actorId, String action, UUID aggregateId, Map<String, ?> metadata) {
        String json;
        try { json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata); }
        catch (Exception ignored) { json = "{}"; }
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result, metadata)
            VALUES (:id, :actorId, :action, 'APPLICATION', :aggregateId, 'SUCCESS', CAST(:metadata AS jsonb))
            """, Map.of("id", UUID.randomUUID(), "actorId", actorId, "action", action,
                "aggregateId", aggregateId, "metadata", json));
    }

    static record AttendanceState(String persisted, String detail, String presented) {
        static AttendanceState from(String value) {
            if (value == null) throw new IllegalArgumentException("Estado de asistencia obligatorio");
            return switch (value) {
                case "PENDING", "ASSIGNED" -> new AttendanceState("ASSIGNED", null, "PENDING");
                case "PRESENT", "ATTENDED" -> new AttendanceState("ATTENDED", null, "PRESENT");
                case "LATE" -> new AttendanceState("ATTENDED", "LATE", "LATE");
                case "ABSENT" -> new AttendanceState("ABSENT", null, "ABSENT");
                case "COULD_NOT_ENTER" -> new AttendanceState("ABSENT", "COULD_NOT_ENTER", "COULD_NOT_ENTER");
                default -> throw new IllegalArgumentException("Estado de asistencia inválido");
            };
        }
        static String presented(String persisted, String detail) {
            return detail == null ? from(persisted).presented() : detail;
        }
    }

    private record GroupRow(UUID groupId, UUID roomId, String roomName, String code, Instant startsAt, Instant endsAt,
                            String status, int capacity, long version, int memberCount, int present, int pending,
                            int absent) {}
    private static final class RoomBuilder {
        private final UUID roomId; private final String name; private final List<ControlTowerGroup> groups = new ArrayList<>();
        private RoomBuilder(UUID roomId, String name) { this.roomId = roomId; this.name = name; }
        private ControlTowerRoom view() { return new ControlTowerRoom(roomId, name, groups); }
    }
    public record ControlTowerDay(UUID processId, LocalDate date, String timezone, long serverSequence,
                                  Summary summary, List<ControlTowerRoom> rooms) {}
    public record Summary(int applicants, int present, int groupsInProgress, int groupsValidated, int openIncidents) {}
    public record ControlTowerRoom(UUID roomId, String name, List<ControlTowerGroup> groups) {}
    public record ControlTowerGroup(UUID groupId, String code, Instant startsAt, Instant endsAt,
                                    String status, int capacity, int memberCount, Attendance attendance,
                                    Map<String, String> instrumentProgress, long version) {}
    public record Attendance(int present, int pending, int absent) {}
    public record AttendanceUpdate(UUID groupId, UUID applicationId, String status, String reasonCode,
                                   long version, Instant recordedAt) {}
}
