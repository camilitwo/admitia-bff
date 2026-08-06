package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderFlowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prekinder")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderFlowController {
    private final PrekinderFlowService flow;

    public PrekinderFlowController(PrekinderFlowService flow) { this.flow = flow; }

    @GetMapping("/processes/{processId}/waves")
    public Map<String, Object> waves(@PathVariable UUID processId) {
        return ok(flow.waves(processId));
    }

    @PutMapping("/waves/{waveId}")
    public Map<String, Object> configureWave(@PathVariable UUID waveId, @Valid @RequestBody WaveCommand command) {
        return ok(flow.configureWave(waveId, command.opensAt(), command.closesAt(), command.status(), command.expectedVersion()));
    }

    @PostMapping("/applications")
    public Map<String, Object> submit(@Valid @RequestBody SubmitApplication command) {
        var siblings = command.eligibility().siblings() == null ? List.<PrekinderFlowService.SiblingDeclaration>of()
            : command.eligibility().siblings().stream().map(item ->
                new PrekinderFlowService.SiblingDeclaration(item.name(), item.rut(), item.currentGrade())).toList();
        var eligibility = new PrekinderFlowService.EligibilityDeclaration(siblings,
            command.eligibility().employeeParent(), alumni(command.eligibility().fatherAlumni()),
            alumni(command.eligibility().motherAlumni()));
        return ok(flow.submitApplication(new PrekinderFlowService.SubmitApplication(command.processId(), command.rut(),
            command.firstName(), command.paternalLastName(), command.maternalLastName(), command.birthDate(),
            command.familyEmail(), command.fatherEmail(), command.motherEmail(), eligibility)));
    }

    @GetMapping("/applications")
    public Map<String, Object> applications(@RequestParam UUID processId) { return ok(flow.applications(processId)); }

    @PutMapping("/applications/{applicationId}/eligibility")
    public Map<String, Object> review(@PathVariable UUID applicationId, @Valid @RequestBody EligibilityReview command) {
        return ok(flow.reviewEligibility(applicationId, command.decision(), command.reason(), command.expectedVersion()));
    }

    @GetMapping("/professionals")
    public Map<String, Object> professionals() { return ok(flow.professionals()); }

    @PostMapping("/professionals")
    public Map<String, Object> professional(@Valid @RequestBody ProfessionalCommand command) {
        return ok(flow.saveProfessional(new PrekinderFlowService.ProfessionalCommand(command.professionalId(),
            command.legacyUserId(), command.displayName(), command.email(), command.specialty(), command.roleCode(),
            command.active(), command.expectedVersion())));
    }

    @GetMapping("/professionals/{professionalId}/availability")
    public Map<String, Object> availability(@PathVariable UUID professionalId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ok(flow.availability(professionalId, from, to));
    }

    @PostMapping("/professionals/{professionalId}/availability")
    public Map<String, Object> availability(@PathVariable UUID professionalId,
        @Valid @RequestBody AvailabilityCommand command) {
        return ok(flow.saveAvailability(professionalId, command.startsAt(), command.endsAt(), command.status()));
    }

    @GetMapping("/processes/{processId}/rooms")
    public Map<String, Object> rooms(@PathVariable UUID processId) { return ok(flow.rooms(processId)); }

    @PostMapping("/processes/{processId}/rooms")
    public Map<String, Object> room(@PathVariable UUID processId, @Valid @RequestBody RoomCommand command) {
        return ok(flow.createRoom(processId, command.code(), command.name(), command.capacity()));
    }

    @GetMapping("/processes/{processId}/groups")
    public Map<String, Object> groups(@PathVariable UUID processId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ok(flow.groups(processId, date));
    }

    @GetMapping("/processes/{processId}/schedule")
    public Map<String, Object> schedule(@PathVariable UUID processId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ok(flow.schedule(processId, date));
    }

    @PostMapping("/groups")
    public Map<String, Object> group(@Valid @RequestBody GroupCommand command) {
        return ok(flow.createGroup(new PrekinderFlowService.GroupCommand(command.processId(), command.roomId(),
            command.stage(), command.code(), command.startsAt(), command.durationMinutes(), command.capacity(),
            command.requiredEvaluators())));
    }

    @PutMapping("/groups/{groupId}/schedule")
    public Map<String, Object> reschedule(@PathVariable UUID groupId, @Valid @RequestBody RescheduleCommand command) {
        return ok(flow.rescheduleGroup(groupId, command.roomId(), command.startsAt(), command.durationMinutes(),
            command.reason(), command.expectedVersion()));
    }

    @PutMapping("/groups/{groupId}/configuration")
    public Map<String, Object> configureGroup(@PathVariable UUID groupId,
        @Valid @RequestBody GroupConfigurationCommand command) {
        return ok(flow.configureGroup(groupId, command.capacity(), command.requiredEvaluators(), command.reason(),
            command.expectedVersion()));
    }

    @PostMapping("/groups/{groupId}/members/{applicationId}")
    public Map<String, Object> member(@PathVariable UUID groupId, @PathVariable UUID applicationId) {
        return ok(flow.addMember(groupId, applicationId));
    }

    @PostMapping("/groups/{groupId}/evaluators/{evaluatorId}")
    public Map<String, Object> evaluator(@PathVariable UUID groupId, @PathVariable UUID evaluatorId) {
        return ok(flow.assignEvaluator(groupId, evaluatorId));
    }

    @PutMapping("/groups/{groupId}/confirmation")
    public Map<String, Object> confirm(@PathVariable UUID groupId, @Valid @RequestBody VersionCommand command) {
        return ok(flow.confirmGroup(groupId, command.expectedVersion()));
    }

    @PutMapping("/groups/{groupId}/completion")
    public Map<String, Object> complete(@PathVariable UUID groupId, @Valid @RequestBody VersionCommand command) {
        return ok(flow.completeGroup(groupId, command.expectedVersion()));
    }

    @GetMapping("/me/agenda")
    public Map<String, Object> agenda(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ok(flow.myAgenda(date));
    }

    @GetMapping("/me/results")
    public Map<String, Object> results() { return ok(flow.myPublishedResults()); }

    @PutMapping("/applications/{applicationId}/decision")
    public Map<String, Object> decision(@PathVariable UUID applicationId, @Valid @RequestBody DecisionCommand command) {
        return ok(flow.decide(applicationId, command.decision(), command.note()));
    }

    @PutMapping("/applications/{applicationId}/decision/correction")
    public Map<String, Object> correction(@PathVariable UUID applicationId, @Valid @RequestBody CorrectionCommand command) {
        return ok(flow.correctPublishedDecision(applicationId, command.decision(), command.note(), command.reason()));
    }

    @PostMapping("/processes/{processId}/publication-batches")
    public Map<String, Object> publication(@PathVariable UUID processId, @Valid @RequestBody PublicationCommand command) {
        return ok(flow.schedulePublication(processId, command.scheduledAt()));
    }

    @GetMapping("/processes/{processId}/dashboard")
    public Map<String, Object> dashboard(@PathVariable UUID processId) { return ok(flow.dashboard(processId)); }

    @GetMapping("/processes/{processId}/rubrics")
    public Map<String, Object> rubrics(@PathVariable UUID processId) { return ok(flow.rubrics(processId)); }

    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return ok(flow.auditTrail(limit));
    }

    private static PrekinderFlowService.AlumniDeclaration alumni(AlumniDeclaration value) {
        return value == null ? new PrekinderFlowService.AlumniDeclaration("NO_ALUMNI", null, null, null)
            : new PrekinderFlowService.AlumniDeclaration(value.status(), value.graduationYear(), value.lastGrade(), value.withdrawalReason());
    }

    private static Map<String, Object> ok(Object data) { return Map.of("success", true, "data", data); }

    public record WaveCommand(@NotNull Instant opensAt, @NotNull Instant closesAt, @NotBlank String status,
                              @Min(0) long expectedVersion) {}
    public record SiblingDeclaration(@NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 16) String rut,
                                     @NotBlank @Size(max = 64) String currentGrade) {}
    public record AlumniDeclaration(@NotBlank String status, Integer graduationYear, String lastGrade,
                                    @Size(max = 1000) String withdrawalReason) {}
    public record EligibilityDeclaration(List<@Valid SiblingDeclaration> siblings, String employeeParent,
                                         @Valid AlumniDeclaration fatherAlumni, @Valid AlumniDeclaration motherAlumni) {}
    public record SubmitApplication(@NotNull UUID processId, @NotBlank @Size(max = 16) String rut,
        @NotBlank @Size(max = 100) String firstName, @NotBlank @Size(max = 100) String paternalLastName,
        @Size(max = 100) String maternalLastName, @NotNull LocalDate birthDate,
        @Email @Size(max = 254) String familyEmail,
        @Email @Size(max = 254) String fatherEmail, @Email @Size(max = 254) String motherEmail,
        @NotNull @Valid EligibilityDeclaration eligibility) {}
    public record EligibilityReview(@NotBlank String decision, @Size(max = 2000) String reason,
                                    @Min(0) long expectedVersion) {}
    public record ProfessionalCommand(UUID professionalId, Long legacyUserId, @NotBlank @Size(max = 160) String displayName,
        @Email @NotBlank @Size(max = 254) String email, @Size(max = 96) String specialty, @NotBlank String roleCode,
        boolean active, @Min(0) long expectedVersion) {}
    public record AvailabilityCommand(@NotNull Instant startsAt, @NotNull Instant endsAt, @NotBlank String status) {}
    public record RoomCommand(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 160) String name,
                              @Min(9) @Max(200) int capacity) {}
    public record GroupCommand(@NotNull UUID processId, @NotNull UUID roomId, @NotBlank String stage,
                               @NotBlank @Size(max = 64) String code, @NotNull Instant startsAt,
                               @Min(10) @Max(240) Integer durationMinutes,
                               @Min(1) @Max(30) Integer capacity,
                               @Min(1) @Max(12) Integer requiredEvaluators) {}
    public record RescheduleCommand(@NotNull UUID roomId, @NotNull Instant startsAt,
                                    @Min(10) @Max(240) Integer durationMinutes,
                                    @Size(max = 2000) String reason, @Min(0) long expectedVersion) {}
    public record GroupConfigurationCommand(@Min(1) @Max(30) int capacity,
                                            @Min(1) @Max(12) int requiredEvaluators,
                                            @Size(max = 2000) String reason,
                                            @Min(0) long expectedVersion) {}
    public record VersionCommand(@Min(0) long expectedVersion) {}
    public record DecisionCommand(@NotBlank String decision, @Size(max = 4000) String note) {}
    public record CorrectionCommand(@NotBlank String decision, @Size(max = 4000) String note,
                                    @NotBlank @Size(max = 2000) String reason) {}
    public record PublicationCommand(@NotNull @Future Instant scheduledAt) {}
}
