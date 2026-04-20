package cl.mtn.admitiabff.domain.student;

import cl.mtn.admitiabff.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "students")
public class StudentEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "paternal_last_name")
    private String paternalLastName;

    @Column(name = "maternal_last_name")
    private String maternalLastName;

    @Column
    private String rut;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column
    private String email;

    @Column(columnDefinition = "text")
    private String address;

    @Column(name = "grade_applied")
    private String gradeApplied;

    @Column(name = "target_school")
    private String targetSchool;

    @Column(name = "current_school")
    private String currentSchool;

    @Column(name = "special_needs")
    private boolean specialNeeds;

    @Column(name = "special_needs_description", columnDefinition = "text")
    private String specialNeedsDescription;

    @Column(name = "additional_notes", columnDefinition = "text")
    private String additionalNotes;

    @Column
    private Integer age;

    @Column
    private String pais;

    @Column
    private String region;

    @Column
    private String comuna;

    @Column(name = "admission_preference")
    private String admissionPreference;

    @Column(name = "is_employee_child")
    private boolean employeeChild;

    @Column(name = "employee_parent_name")
    private String employeeParentName;

    @Column(name = "is_alumni_child")
    private boolean alumniChild;

    @Column(name = "alumni_parent_year")
    private Integer alumniParentYear;

    @Column(name = "is_inclusion_student")
    private boolean inclusionStudent;

    @Column(name = "inclusion_type")
    private String inclusionType;

    @Column(name = "inclusion_notes", columnDefinition = "text")
    private String inclusionNotes;
}
