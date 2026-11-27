package cl.mtn.admitiabff.domain.person;

import cl.mtn.admitiabff.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "parents")
public class ParentEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "paternal_last_name")
    private String paternalLastName;

    @Column(name = "maternal_last_name")
    private String maternalLastName;

    @Column(name = "marital_status")
    private String maritalStatus;

    @Column
    private String rut;

    @Column
    private String email;

    @Column
    private String phone;

    @Column(columnDefinition = "text")
    private String address;

    @Column
    private String profession;

    @Column
    private String workplace;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "parent_type", nullable = false)
    private String parentType;
}
