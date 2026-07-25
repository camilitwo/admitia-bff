package cl.mtn.admitiabff.domain.grade;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "grade_availability")
public class GradeAvailabilityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "grade_level", nullable = false, unique = true, length = 50)
    private String gradeLevel;

    @Column(name = "has_vacancy_m", nullable = false)
    private Boolean hasVacancyM;

    @Column(name = "has_vacancy_f", nullable = false)
    private Boolean hasVacancyF;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    public GradeAvailabilityEntity() {}

    public GradeAvailabilityEntity(String gradeLevel, Boolean hasVacancyM, Boolean hasVacancyF) {
        this.gradeLevel = gradeLevel;
        this.hasVacancyM = hasVacancyM;
        this.hasVacancyF = hasVacancyF;
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    @PreUpdate
    public void updateTimestamp() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGradeLevel() { return gradeLevel; }
    public void setGradeLevel(String gradeLevel) { this.gradeLevel = gradeLevel; }

    public Boolean getHasVacancyM() { return hasVacancyM; }
    public void setHasVacancyM(Boolean hasVacancyM) { this.hasVacancyM = hasVacancyM; }

    public Boolean getHasVacancyF() { return hasVacancyF; }
    public void setHasVacancyF(Boolean hasVacancyF) { this.hasVacancyF = hasVacancyF; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
