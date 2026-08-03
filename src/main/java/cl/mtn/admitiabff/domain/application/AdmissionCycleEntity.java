package cl.mtn.admitiabff.domain.application;

import cl.mtn.admitiabff.domain.common.AdmissionCycleStatus;
import cl.mtn.admitiabff.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "admission_cycles")
public class AdmissionCycleEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "academic_year", nullable = false, unique = true)
    private Integer academicYear;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdmissionCycleStatus status = AdmissionCycleStatus.OPEN;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by_user_id")
    private Long closedByUserId;

    @Column(name = "total_applications", nullable = false)
    private long totalApplications;

    @Column(name = "queued_count", nullable = false)
    private long queuedCount;

    @Column(name = "sent_count", nullable = false)
    private long sentCount;

    @Column(name = "failed_count", nullable = false)
    private long failedCount;

    @Version
    @Column(nullable = false)
    private long version;
}
