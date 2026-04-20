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

@Getter
@Setter
@Entity
@Table(name = "supporters")
public class SupporterEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column
    private String rut;

    @Column
    private String email;

    @Column
    private String phone;

    @Column
    private String relationship;

    @Column(columnDefinition = "text")
    private String address;
}
