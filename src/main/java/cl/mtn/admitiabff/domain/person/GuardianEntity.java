package cl.mtn.admitiabff.domain.person;

import cl.mtn.admitiabff.domain.common.BaseEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "guardians")
public class GuardianEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

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

    @Column
    private String profession;

    @Column
    private String workplace;
}
