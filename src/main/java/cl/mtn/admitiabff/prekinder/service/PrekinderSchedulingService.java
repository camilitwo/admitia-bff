package cl.mtn.admitiabff.prekinder.service;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
public class PrekinderSchedulingService {
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);
    private static final Set<String> GROUP_3_INSTRUMENTS = Set.of("ACADEMIC", "PSYCHOLOGY", "ENTRY_INDICATORS");
    private static final Set<String> GROUP_9_INSTRUMENTS = Set.of("PSYCHOMOTOR", "GROUP_OBSERVATION");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PrekinderAccessService access;
    private final PrekinderFlowService flow;
    private final ObjectMapper mapper;

    public PrekinderSchedulingService(@Qualifier("prekinderJdbc") NamedParameterJdbcTemplate jdbc,
                                      @Qualifier("prekinderTransactionManager") PlatformTransactionManager manager,
                                      PrekinderAccessService access, PrekinderFlowService flow,
                                      ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.access = access;
        this.flow = flow;
        this.mapper = mapper;
    }

    public SchedulePreview preview(UUID processId, LocalDate date, String requestedStage,
                                   List<UUID> requestedApplicationIds) {
        PrekinderActor actor = access.requireAdmin();
        String stage = normalizeStage(requestedStage);
        ScheduleConfiguration configuration = configuration(processId, stage);
        List<ApplicationCandidate> applications = applications(processId, stage, requestedApplicationIds);
        List<RoomCandidate> rooms = rooms(processId);
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (applications.isEmpty()) blockers.add("No hay postulantes pendientes para esta instancia");
        if (rooms.isEmpty()) blockers.add("No hay salas activas para el proceso");

        List<GroupPlan> groups = new ArrayList<>();
        List<InterviewPlan> interviews = new ArrayList<>();
        List<RoomReservation> plannedRooms = new ArrayList<>();
        List<EvaluatorReservation> plannedEvaluators = new ArrayList<>();
        ZoneId zone = ZoneId.of(configuration.timezone());
        List<TimeSlot> slots = slots(date, configuration, zone);
        int cursor = 0;

        while (cursor < applications.size() && blockers.isEmpty()) {
            List<ApplicationCandidate> chunk = applications.subList(cursor,
                Math.min(cursor + configuration.groupSize(), applications.size()));
            Placement placement = findPlacement(processId, stage, slots, rooms, configuration.groupSize(),
                configuration.requiredEvaluators(),
                plannedRooms, plannedEvaluators);
            if (placement == null) {
                blockers.add("No existe un bloque con sala y equipo evaluador disponible para "
                    + (applications.size() - cursor) + " postulante(s)");
                break;
            }
            String code = "AUTO-" + date.toString().replace("-", "") + "-" + stage.substring(stage.length() - 1)
                + "-" + String.format("%02d", groups.size() + 1);
            GroupPlan group = new GroupPlan(code, stage, placement.room().roomId(), placement.slot().startsAt(),
                placement.slot().endsAt(), chunk.stream().map(ApplicationCandidate::applicationId).toList(),
                placement.evaluatorIds(), configuration.groupSize(), configuration.requiredEvaluators());
            groups.add(group);
            plannedRooms.add(new RoomReservation(group.roomId(), group.startsAt(), group.endsAt()));
            group.evaluatorIds().forEach(id -> plannedEvaluators.add(
                new EvaluatorReservation(id, group.startsAt(), group.endsAt())));
            cursor += chunk.size();
        }

        if (blockers.isEmpty()) {
            for (GroupPlan group : groups) {
                for (UUID applicationId : group.applicationIds()) {
                    if (hasFamilyInterview(applicationId)) continue;
                    InterviewPlan interview = findInterview(processId, applicationId, slots, rooms,
                        group.startsAt(), group.endsAt(), plannedRooms, plannedEvaluators);
                    if (interview == null) {
                        blockers.add("No existe un bloque independiente para la entrevista familiar de " + applicationId);
                        break;
                    }
                    interviews.add(interview);
                    plannedRooms.add(new RoomReservation(interview.roomId(), interview.startsAt(), interview.endsAt()));
                    plannedEvaluators.add(new EvaluatorReservation(interview.evaluatorId(),
                        interview.startsAt(), interview.endsAt()));
                }
                if (!blockers.isEmpty()) break;
            }
        }

        if (applications.size() % configuration.groupSize() != 0 && !applications.isEmpty())
            warnings.add("El último grupo queda bajo la capacidad sugerida; puede reorganizarse antes de confirmar");

        UUID planId = UUID.randomUUID();
        SchedulePayload payload = new SchedulePayload(groups, interviews);
        jdbc.update("""
            INSERT INTO schedule_plan_drafts(plan_id, process_id, evaluation_date, stage,
                configuration_version, plan_payload, blockers, warnings, created_by, expires_at)
            VALUES (:planId, :processId, :date, :stage, :configurationVersion,
                CAST(:payload AS jsonb), CAST(:blockers AS jsonb), CAST(:warnings AS jsonb), :actorId, :expiresAt)
            """, new MapSqlParameterSource().addValue("planId", planId).addValue("processId", processId)
            .addValue("date", date).addValue("stage", stage)
            .addValue("configurationVersion", configuration.version()).addValue("payload", json(payload))
            .addValue("blockers", json(blockers)).addValue("warnings", json(warnings))
            .addValue("actorId", actor.id()).addValue("expiresAt", Timestamp.from(Instant.now().plus(PREVIEW_TTL))));
        audit(actor.id(), "SCHEDULE_PREVIEW_CREATED", planId,
            Map.of("processId", processId, "groups", groups.size(), "interviews", interviews.size()));
        return new SchedulePreview(planId, processId, date, stage, configuration.version(), groups, interviews,
            blockers, warnings, Instant.now().plus(PREVIEW_TTL), false);
    }

    public SchedulePreview confirm(UUID planId) {
        PrekinderActor actor = access.requireAdmin();
        return transactions.execute(status -> {
            StoredPlan stored = jdbc.queryForObject("""
                SELECT plan_id, process_id, evaluation_date, stage, configuration_version,
                       plan_payload::text, blockers::text, warnings::text, status, expires_at
                  FROM schedule_plan_drafts WHERE plan_id = :planId FOR UPDATE
                """, Map.of("planId", planId), (rs, row) -> new StoredPlan(
                    rs.getObject("plan_id", UUID.class), rs.getObject("process_id", UUID.class),
                    rs.getObject("evaluation_date", LocalDate.class), rs.getString("stage"),
                    rs.getLong("configuration_version"), rs.getString("plan_payload"),
                    readStrings(rs.getString("blockers")), readStrings(rs.getString("warnings")),
                    rs.getString("status"), rs.getTimestamp("expires_at").toInstant()));
            if (stored == null) throw new IllegalArgumentException("La vista previa no existe");
            if (!"PREVIEW".equals(stored.status()))
                throw PrekinderDomainException.conflict("SCHEDULE_PLAN_CLOSED", "La vista previa ya fue procesada");
            if (stored.expiresAt().isBefore(Instant.now())) {
                jdbc.update("UPDATE schedule_plan_drafts SET status = 'EXPIRED' WHERE plan_id = :planId",
                    Map.of("planId", planId));
                throw PrekinderDomainException.conflict("SCHEDULE_PLAN_EXPIRED", "La vista previa venció; genera una nueva");
            }
            if (!stored.blockers().isEmpty())
                throw PrekinderDomainException.conflict("SCHEDULE_PLAN_BLOCKED", "La vista previa contiene bloqueos");
            Long currentVersion = jdbc.queryForObject(
                "SELECT version FROM prekinder_process_configuration WHERE process_id = :processId FOR UPDATE",
                Map.of("processId", stored.processId()), Long.class);
            if (currentVersion == null || currentVersion != stored.configurationVersion())
                throw PrekinderDomainException.conflict("CONFIGURATION_CHANGED",
                    "La configuración cambió; genera una nueva vista previa");

            SchedulePayload payload = readPayload(stored.payload());
            List<GroupPlan> persistedGroups = new ArrayList<>();
            for (GroupPlan item : payload.groups()) {
                var created = flow.createAssignedGroup(new PrekinderFlowService.GroupCommand(stored.processId(),
                    item.roomId(), item.stage(), item.code() + "-" + shortId(planId), item.startsAt(),
                    Math.toIntExact(Duration.between(item.startsAt(), item.endsAt()).toMinutes()),
                    item.capacity(), item.requiredEvaluators()), item.applicationIds(), item.evaluatorIds());
                flow.confirmGroup(created.groupId(), created.version());
                persistedGroups.add(item);
            }
            for (InterviewPlan item : payload.interviews()) {
                String code = "EF-" + shortId(planId) + "-" + String.format("%03d", payload.interviews().indexOf(item) + 1);
                var created = flow.createAssignedGroup(new PrekinderFlowService.GroupCommand(stored.processId(),
                    item.roomId(), "FAMILY_INTERVIEW", code, item.startsAt(),
                    Math.toIntExact(Duration.between(item.startsAt(), item.endsAt()).toMinutes()), 1, 1),
                    List.of(item.applicationId()), List.of(item.evaluatorId()));
                flow.confirmGroup(created.groupId(), created.version());
                jdbc.update("""
                    INSERT INTO family_interview_appointments(appointment_id, application_id, process_id,
                        evaluator_id, starts_at, ends_at)
                    VALUES (:id, :applicationId, :processId, :evaluatorId, :startsAt, :endsAt)
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                    .addValue("applicationId", item.applicationId()).addValue("processId", stored.processId())
                    .addValue("evaluatorId", item.evaluatorId()).addValue("startsAt", Timestamp.from(item.startsAt()))
                    .addValue("endsAt", Timestamp.from(item.endsAt())));
            }
            jdbc.update("""
                UPDATE schedule_plan_drafts SET status = 'CONFIRMED', confirmed_at = now()
                 WHERE plan_id = :planId
                """, Map.of("planId", planId));
            audit(actor.id(), "SCHEDULE_PLAN_CONFIRMED", planId,
                Map.of("processId", stored.processId(), "groups", payload.groups().size(),
                    "interviews", payload.interviews().size()));
            return new SchedulePreview(planId, stored.processId(), stored.date(), stored.stage(),
                stored.configurationVersion(), persistedGroups, payload.interviews(), stored.blockers(),
                stored.warnings(), stored.expiresAt(), true);
        });
    }

    private Placement findPlacement(UUID processId, String stage, List<TimeSlot> slots, List<RoomCandidate> rooms,
                                    int groupSize, int requiredEvaluators, List<RoomReservation> plannedRooms,
                                    List<EvaluatorReservation> plannedEvaluators) {
        Set<String> instruments = "GROUP_3".equals(stage) ? GROUP_3_INSTRUMENTS : GROUP_9_INSTRUMENTS;
        for (TimeSlot slot : slots) {
            for (RoomCandidate room : rooms) {
                if (room.capacity() < groupSize) continue;
                if (roomOccupied(room.roomId(), slot.startsAt(), slot.endsAt(), plannedRooms)) continue;
                List<UUID> evaluators = availableEvaluators(processId, instruments, slot, plannedEvaluators);
                if (evaluators.size() >= requiredEvaluators)
                    return new Placement(room, slot, evaluators.subList(0, requiredEvaluators));
            }
        }
        return null;
    }

    private InterviewPlan findInterview(UUID processId, UUID applicationId, List<TimeSlot> slots,
                                        List<RoomCandidate> rooms, Instant groupStarts, Instant groupEnds,
                                        List<RoomReservation> plannedRooms,
                                        List<EvaluatorReservation> plannedEvaluators) {
        for (TimeSlot slot : slots) {
            if (overlaps(slot.startsAt(), slot.endsAt(), groupStarts, groupEnds)) continue;
            if (applicantOccupied(applicationId, slot.startsAt(), slot.endsAt())) continue;
            List<UUID> evaluators = availableEvaluators(processId, Set.of("FAMILY_INTERVIEW"), slot, plannedEvaluators);
            if (evaluators.isEmpty()) continue;
            for (RoomCandidate room : rooms) {
                if (!roomOccupied(room.roomId(), slot.startsAt(), slot.endsAt(), plannedRooms))
                    return new InterviewPlan(applicationId, evaluators.getFirst(), room.roomId(),
                        slot.startsAt(), slot.endsAt());
            }
        }
        return null;
    }

    private List<UUID> availableEvaluators(UUID processId, Set<String> instruments, TimeSlot slot,
                                           List<EvaluatorReservation> planned) {
        List<UUID> candidates = jdbc.queryForList("""
            SELECT p.professional_id
              FROM professional_profiles p
             WHERE p.active
               AND CASE p.role_code
                     WHEN 'PK_EVALUATOR_ACADEMIC' THEN 'ACADEMIC'
                     WHEN 'PK_EVALUATOR_PSYCHOMOTOR' THEN 'PSYCHOMOTOR'
                     WHEN 'PK_EVALUATOR_PSYCHOLOGY' THEN 'PSYCHOLOGY'
                     WHEN 'PK_EVALUATOR_ENTRY_INDICATORS' THEN 'ENTRY_INDICATORS'
                     WHEN 'PK_EVALUATOR_GROUP_OBSERVATION' THEN 'GROUP_OBSERVATION'
                     WHEN 'PK_EVALUATOR_FAMILY_INTERVIEW' THEN 'FAMILY_INTERVIEW'
                     WHEN 'PK_EVALUATOR_LEARNING_SUPPORT' THEN 'LEARNING_SUPPORT'
                     WHEN 'PK_EVALUATOR_DAP' THEN 'DAP'
                   END IN (:instruments)
               AND NOT EXISTS (
                   SELECT 1 FROM evaluator_group_bookings booking
                    WHERE booking.evaluator_id = p.professional_id AND booking.active
                      AND booking.starts_at < :endsAt AND booking.ends_at > :startsAt
               )
               AND NOT EXISTS (
                   SELECT 1 FROM professional_availability availability
                    WHERE availability.professional_id = p.professional_id
                      AND availability.status = 'UNAVAILABLE'
                      AND availability.starts_at < :endsAt AND availability.ends_at > :startsAt
               )
               AND (
                   NOT EXISTS (SELECT 1 FROM professional_availability declared
                                WHERE declared.professional_id = p.professional_id)
                   OR EXISTS (SELECT 1 FROM professional_availability available
                               WHERE available.professional_id = p.professional_id
                                 AND available.status = 'AVAILABLE'
                                 AND available.starts_at <= :startsAt AND available.ends_at >= :endsAt)
               )
             ORDER BY p.role_code, p.display_name, p.professional_id
            """, new MapSqlParameterSource().addValue("instruments", instruments)
            .addValue("startsAt", Timestamp.from(slot.startsAt()))
            .addValue("endsAt", Timestamp.from(slot.endsAt())), UUID.class);
        return candidates.stream().filter(id -> planned.stream().noneMatch(item -> item.evaluatorId().equals(id)
            && overlaps(item.startsAt(), item.endsAt(), slot.startsAt(), slot.endsAt()))).toList();
    }

    private boolean roomOccupied(UUID roomId, Instant startsAt, Instant endsAt, List<RoomReservation> planned) {
        if (planned.stream().anyMatch(item -> item.roomId().equals(roomId)
            && overlaps(item.startsAt(), item.endsAt(), startsAt, endsAt))) return true;
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM evaluation_groups
             WHERE room_id = :roomId AND status <> 'CANCELLED'
               AND starts_at < :endsAt AND ends_at > :startsAt
            """, new MapSqlParameterSource().addValue("roomId", roomId)
            .addValue("startsAt", Timestamp.from(startsAt)).addValue("endsAt", Timestamp.from(endsAt)), Long.class);
        return count != null && count > 0;
    }

    private boolean applicantOccupied(UUID applicationId, Instant startsAt, Instant endsAt) {
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM applicant_group_bookings
             WHERE application_id = :applicationId AND active
               AND starts_at < :endsAt AND ends_at > :startsAt
            """, new MapSqlParameterSource().addValue("applicationId", applicationId)
            .addValue("startsAt", Timestamp.from(startsAt)).addValue("endsAt", Timestamp.from(endsAt)), Long.class);
        return count != null && count > 0;
    }

    private boolean hasFamilyInterview(UUID applicationId) {
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM family_interview_appointments
             WHERE application_id = :applicationId AND status IN ('SCHEDULED','COMPLETED')
            """, Map.of("applicationId", applicationId), Long.class);
        return count != null && count > 0;
    }

    private ScheduleConfiguration configuration(UUID processId, String stage) {
        return jdbc.queryForObject("""
            SELECT version, schedule_timezone, schedule_day_start, schedule_day_end,
                   schedule_block_minutes, schedule_max_blocks,
                   academic_group_size, psychomotor_group_size,
                   academic_required_evaluators, psychomotor_required_evaluators
              FROM prekinder_process_configuration WHERE process_id = :processId
            """, Map.of("processId", processId), (rs, row) -> new ScheduleConfiguration(
                rs.getLong("version"), rs.getString("schedule_timezone"),
                rs.getObject("schedule_day_start", LocalTime.class), rs.getObject("schedule_day_end", LocalTime.class),
                rs.getInt("schedule_block_minutes"), rs.getInt("schedule_max_blocks"),
                "GROUP_3".equals(stage) ? rs.getInt("academic_group_size") : rs.getInt("psychomotor_group_size"),
                "GROUP_3".equals(stage) ? rs.getInt("academic_required_evaluators")
                    : rs.getInt("psychomotor_required_evaluators")));
    }

    private List<ApplicationCandidate> applications(UUID processId, String stage, List<UUID> requestedIds) {
        String filter = requestedIds == null || requestedIds.isEmpty() ? "" : " AND a.application_id IN (:ids)";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("processId", processId)
            .addValue("stage", stage);
        if (requestedIds != null && !requestedIds.isEmpty()) params.addValue("ids", requestedIds);
        return jdbc.query("""
            SELECT a.application_id, a.applicant_sex, a.eligibility_category
              FROM applications a
             WHERE a.process_id = :processId AND a.status IN ('READY_TO_SCHEDULE','SCHEDULED')
               AND NOT EXISTS (
                   SELECT 1 FROM evaluation_group_members member
                   JOIN evaluation_groups group_data ON group_data.group_id = member.group_id
                    WHERE member.application_id = a.application_id
                      AND member.status IN ('ASSIGNED','ATTENDED') AND group_data.stage = :stage
                      AND group_data.status <> 'CANCELLED'
               )
            """ + filter + """
             ORDER BY a.applicant_sex,
                      CASE a.eligibility_category WHEN 'STAFF_OR_ALUMNI' THEN 1 WHEN 'NEW_FAMILIES' THEN 2 ELSE 3 END,
                      a.formal_submitted_at, a.folio, a.application_id
            """, params, (rs, row) -> new ApplicationCandidate(rs.getObject("application_id", UUID.class),
                rs.getString("applicant_sex"), rs.getString("eligibility_category")));
    }

    private List<RoomCandidate> rooms(UUID processId) {
        return jdbc.query("""
            SELECT room_id, capacity FROM prekinder_rooms
             WHERE process_id = :processId AND active AND capacity > 0 ORDER BY code, room_id
            """, Map.of("processId", processId), (rs, row) ->
            new RoomCandidate(rs.getObject("room_id", UUID.class), rs.getInt("capacity")));
    }

    private static List<TimeSlot> slots(LocalDate date, ScheduleConfiguration configuration, ZoneId zone) {
        List<TimeSlot> values = new ArrayList<>();
        Instant dayEnd = date.atTime(configuration.dayEnd()).atZone(zone).toInstant();
        Instant cursor = date.atTime(configuration.dayStart()).atZone(zone).toInstant();
        while (values.size() < configuration.maxBlocks()) {
            Instant end = cursor.plus(Duration.ofMinutes(configuration.blockMinutes()));
            if (end.isAfter(dayEnd)) break;
            values.add(new TimeSlot(cursor, end));
            cursor = end;
        }
        return values;
    }

    private static String normalizeStage(String stage) {
        if (!Set.of("GROUP_3", "GROUP_9").contains(stage))
            throw new IllegalArgumentException("La instancia debe ser GROUP_3 o GROUP_9");
        return stage;
    }

    private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return aStart.isBefore(bEnd) && aEnd.isAfter(bStart);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("No fue posible serializar la agenda", exception); }
    }

    private SchedulePayload readPayload(String value) {
        try { return mapper.readValue(value, SchedulePayload.class); }
        catch (Exception exception) { throw new IllegalStateException("La vista previa guardada no es válida", exception); }
    }

    private List<String> readStrings(String value) {
        try { return mapper.readerForListOf(String.class).readValue(value); }
        catch (Exception exception) { throw new IllegalStateException("La vista previa guardada no es válida", exception); }
    }

    private void audit(UUID actorId, String action, UUID aggregateId, Map<String, ?> metadata) {
        jdbc.update("""
            INSERT INTO audit_events(audit_id, actor_id, action, aggregate_type, aggregate_id, result, metadata)
            VALUES (:id, :actorId, :action, 'SCHEDULE_PLAN', :aggregateId, 'SUCCESS', CAST(:metadata AS jsonb))
            """, Map.of("id", UUID.randomUUID(), "actorId", actorId, "action", action,
                "aggregateId", aggregateId, "metadata", json(metadata)));
    }

    private static String shortId(UUID value) { return value.toString().substring(0, 8).toUpperCase(); }

    public record SchedulePreview(UUID planId, UUID processId, LocalDate date, String stage,
        long configurationVersion, List<GroupPlan> groups, List<InterviewPlan> familyInterviews,
        List<String> blockers, List<String> warnings, Instant expiresAt, boolean confirmed) {}
    public record GroupPlan(String code, String stage, UUID roomId, Instant startsAt, Instant endsAt,
        List<UUID> applicationIds, List<UUID> evaluatorIds, int capacity, int requiredEvaluators) {}
    public record InterviewPlan(UUID applicationId, UUID evaluatorId, UUID roomId,
        Instant startsAt, Instant endsAt) {}
    private record SchedulePayload(List<GroupPlan> groups, List<InterviewPlan> interviews) {}
    private record ScheduleConfiguration(long version, String timezone, LocalTime dayStart, LocalTime dayEnd,
        int blockMinutes, int maxBlocks, int groupSize, int requiredEvaluators) {}
    private record ApplicationCandidate(UUID applicationId, String sex, String segment) {}
    private record RoomCandidate(UUID roomId, int capacity) {}
    private record TimeSlot(Instant startsAt, Instant endsAt) {}
    private record Placement(RoomCandidate room, TimeSlot slot, List<UUID> evaluatorIds) {}
    private record RoomReservation(UUID roomId, Instant startsAt, Instant endsAt) {}
    private record EvaluatorReservation(UUID evaluatorId, Instant startsAt, Instant endsAt) {}
    private record StoredPlan(UUID planId, UUID processId, LocalDate date, String stage,
        long configurationVersion, String payload, List<String> blockers, List<String> warnings,
        String status, Instant expiresAt) {}
}
