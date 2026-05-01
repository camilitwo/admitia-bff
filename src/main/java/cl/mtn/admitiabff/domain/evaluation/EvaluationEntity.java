package cl.mtn.admitiabff.domain.evaluation;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.BaseEntity;
import cl.mtn.admitiabff.domain.common.EvaluationStatus;
import cl.mtn.admitiabff.domain.user.UserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "evaluations")
public class EvaluationEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    private ApplicationEntity application;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evaluator_id")
    private UserEntity evaluator;

    @Column(name = "evaluation_type", nullable = false)
    private String evaluationType;

    @Column
    private String subject;

    @Column(name = "educational_level")
    private String educationalLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EvaluationStatus status = EvaluationStatus.PENDING;

    @Column(name = "evaluation_date")
    private LocalDateTime evaluationDate;

    @Column(precision = 10, scale = 2)
    private BigDecimal score;

    @Column(name = "max_score", precision = 10, scale = 2)
    private BigDecimal maxScore;

    @Column(columnDefinition = "text")
    private String recommendations;

    @Column(columnDefinition = "text")
    private String observations;

    @Column(name = "cancellation_reason", columnDefinition = "text")
    private String cancellationReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "interview_data", columnDefinition = "jsonb")
    private String interviewData;

    @Column(name = "family_interview_score", precision = 10, scale = 2)
    private BigDecimal familyInterviewScore;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
