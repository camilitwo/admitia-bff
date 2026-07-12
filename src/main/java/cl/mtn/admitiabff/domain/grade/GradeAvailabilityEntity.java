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

    @Column(name = "has_vacancy", nullable = false)
    private Boolean hasVacancy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    public GradeAvailabilityEntity() {}

    public GradeAvailabilityEntity(String gradeLevel, Boolean hasVacancy) {
        this.gradeLevel = gradeLevel;
        this.hasVacancy = hasVacancy;
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

    public Boolean getHasVacancy() { return hasVacancy; }
    public void setHasVacancy(Boolean hasVacancy) { this.hasVacancy = hasVacancy; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
