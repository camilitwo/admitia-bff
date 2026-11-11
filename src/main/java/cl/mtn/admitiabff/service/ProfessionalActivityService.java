package cl.mtn.admitiabff.service;

import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.evaluation.EvaluationEntity;
import cl.mtn.admitiabff.domain.interview.InterviewEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.EvaluationRepository;
import cl.mtn.admitiabff.repository.InterviewRepository;
import cl.mtn.admitiabff.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProfessionalActivityService {
    private static final List<Role> PROFESSIONAL_ROLES = List.of(
        Role.TEACHER,
        Role.PSYCHOLOGIST,
        Role.CYCLE_DIRECTOR,
        Role.INTERVIEWER
    );

    private final UserRepository userRepository;
    private final EvaluationRepository evaluationRepository;
    private final InterviewRepository interviewRepository;
    private final String activeProcessCode;

    public ProfessionalActivityService(
        UserRepository userRepository,
        EvaluationRepository evaluationRepository,
        InterviewRepository interviewRepository,
        @Value("${app.admission-cycle.process-code:KIV-2027-02}") String activeProcessCode
    ) {
        this.userRepository = userRepository;
        this.evaluationRepository = evaluationRepository;
        this.interviewRepository = interviewRepository;
        this.activeProcessCode = activeProcessCode;
    }

    public ProfessionalActivityResponse getActivity(String processCode, Role role) {
        String effectiveProcessCode = processCode == null || processCode.isBlank()
            ? activeProcessCode
            : processCode;
        List<UserEntity> professionals = userRepository
            .findByRoleInOrderByRoleAscFirstNameAscLastNameAsc(PROFESSIONAL_ROLES)
            .stream()
            .filter(UserEntity::isActive)
            .filter(user -> role == null || user.getRole() == role)
            .toList();

        Map<Long, MutableActivity> activityByUser = new LinkedHashMap<>();
        professionals.forEach(user -> activityByUser.put(user.getId(), new MutableActivity(user)));

        List<EvaluationEntity> processEvaluations = evaluationRepository
            .findActiveByProcessOrderByCreatedAtDesc(effectiveProcessCode);
        Map<Long, EvaluationStatus> familyStatusByApplication = new LinkedHashMap<>();
        for (EvaluationEntity evaluation : processEvaluations) {
            if ("FAMILY_INTERVIEW".equals(evaluation.getEvaluationType()) && evaluation.getApplication() != null) {
                familyStatusByApplication.merge(
                    evaluation.getApplication().getId(),
                    evaluation.getStatus(),
                    ProfessionalActivityService::preferCompleted
                );
            }
            if (evaluation.getEvaluator() == null || "FAMILY_INTERVIEW".equals(evaluation.getEvaluationType())) continue;
            MutableActivity activity = activityByUser.get(evaluation.getEvaluator().getId());
            if (activity != null) activity.addEvaluation(evaluation);
        }

        for (InterviewEntity interview : interviewRepository.findActiveByProcessOrderByCreatedAtDesc(effectiveProcessCode)) {
            if (!"FAMILY".equals(interview.getInterviewType()) || interview.getApplication() == null) continue;
            boolean cancelled = interview.getStatus() == InterviewStatus.CANCELLED;
            boolean noShow = interview.getStatus() == InterviewStatus.REJECTED_BY_FAMILY;
            boolean completed = interview.getStatus() == InterviewStatus.COMPLETED
                || familyStatusByApplication.get(interview.getApplication().getId()) == EvaluationStatus.COMPLETED;
            addFamilyInterview(activityByUser, interview.getInterviewer(), completed, cancelled, noShow);
            addFamilyInterview(activityByUser, interview.getSecondInterviewer(), completed, cancelled, noShow);
        }

        return new ProfessionalActivityResponse(
            effectiveProcessCode,
            activityByUser.values().stream().map(MutableActivity::toResponse).toList()
        );
    }

    private static EvaluationStatus preferCompleted(EvaluationStatus first, EvaluationStatus second) {
        return first == EvaluationStatus.COMPLETED ? first : second;
    }

    private static void addFamilyInterview(
        Map<Long, MutableActivity> activityByUser,
        UserEntity interviewer,
        boolean completed,
        boolean cancelled,
        boolean noShow
    ) {
        if (interviewer == null) return;
        MutableActivity activity = activityByUser.get(interviewer.getId());
        if (activity != null) activity.addFamilyInterview(completed, cancelled, noShow);
    }

    public record ProfessionalActivityResponse(
        String processCode,
        List<ProfessionalActivity> professionals
    ) {}

    public record ProfessionalActivity(
        long userId,
        String fullName,
        String email,
        String role,
        int assigned,
        int completed,
        int pending,
        int inProgress,
        int cancelled,
        int noShow,
        double completionRate,
        ActivitySummary interviews,
        ActivitySummary evaluations,
        ActivitySummary reports,
        Map<String, ActivitySummary> breakdown
    ) {}

    public record ActivitySummary(
        int assigned,
        int completed,
        int pending,
        int inProgress,
        int cancelled,
        int noShow
    ) {}

    private static final class MutableActivity {
        private final UserEntity user;
        private final Map<String, MutableSummary> breakdown = new LinkedHashMap<>();
        private final MutableSummary evaluations = new MutableSummary();
        private final MutableSummary interviews = new MutableSummary();
        private final MutableSummary reports = new MutableSummary();

        private MutableActivity(UserEntity user) {
            this.user = user;
        }

        private void addEvaluation(EvaluationEntity evaluation) {
            if ("CYCLE_DIRECTOR_REPORT".equals(evaluation.getEvaluationType())) {
                reports.add(evaluation.getStatus());
            } else if (evaluation.getEvaluationType() != null && evaluation.getEvaluationType().endsWith("_INTERVIEW")) {
                interviews.add(evaluation.getStatus());
            } else {
                evaluations.add(evaluation.getStatus());
            }
            breakdown.computeIfAbsent(evaluation.getEvaluationType(), ignored -> new MutableSummary())
                .add(evaluation.getStatus());
        }

        private void addFamilyInterview(boolean completed, boolean cancelled, boolean noShow) {
            interviews.addInterview(completed, cancelled, noShow);
            breakdown.computeIfAbsent("FAMILY_INTERVIEW", ignored -> new MutableSummary())
                .addInterview(completed, cancelled, noShow);
        }

        private ProfessionalActivity toResponse() {
            ActivitySummary evaluationSummary = evaluations.toSummary();
            ActivitySummary interviewSummary = interviews.toSummary();
            ActivitySummary reportSummary = reports.toSummary();
            int assigned = evaluationSummary.assigned() + interviewSummary.assigned();
            int completed = evaluationSummary.completed() + interviewSummary.completed();
            double completionRate = assigned == 0 ? 0 : completed * 100.0 / assigned;
            Map<String, ActivitySummary> immutableBreakdown = new LinkedHashMap<>();
            breakdown.forEach((type, summary) -> immutableBreakdown.put(type, summary.toSummary()));
            return new ProfessionalActivity(
                user.getId(),
                (user.getFirstName() + " " + user.getLastName()).trim(),
                user.getEmail(),
                user.getRole().name(),
                assigned,
                completed,
                evaluationSummary.pending() + interviewSummary.pending(),
                evaluationSummary.inProgress() + interviewSummary.inProgress(),
                evaluationSummary.cancelled() + interviewSummary.cancelled(),
                evaluationSummary.noShow() + interviewSummary.noShow(),
                completionRate,
                interviewSummary,
                evaluationSummary,
                reportSummary,
                immutableBreakdown
            );
        }
    }

    private static final class MutableSummary {
        private int assigned;
        private int completed;
        private int pending;
        private int inProgress;
        private int cancelled;
        private int noShow;

        private void add(EvaluationStatus status) {
            if (status == EvaluationStatus.CANCELLED) {
                cancelled++;
                return;
            }
            assigned++;
            if (status == EvaluationStatus.COMPLETED) completed++;
            if (status == EvaluationStatus.PENDING) pending++;
            if (status == EvaluationStatus.IN_PROGRESS) inProgress++;
        }

        private void addInterview(boolean isCompleted, boolean isCancelled, boolean isNoShow) {
            if (isCancelled) {
                cancelled++;
                return;
            }
            if (isNoShow) {
                noShow++;
                return;
            }
            assigned++;
            if (isCompleted) completed++;
            else pending++;
        }

        private ActivitySummary toSummary() {
            return new ActivitySummary(assigned, completed, pending, inProgress, cancelled, noShow);
        }
    }
}
