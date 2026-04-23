package cl.mtn.admitiabff.domain.user;

import cl.mtn.admitiabff.domain.common.BaseEntity;
import cl.mtn.admitiabff.domain.common.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

@Getter
@Setter
@Entity
@Table(name = "users")
public class UserEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column
    private String subject;

    @Column(name = "educational_level")
    private String educationalLevel;

    @Column
    private String rut;

    @Column
    private String phone;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "preferences_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String preferencesJson;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "firebase_uid", unique = true)
    private String firebaseUid;
}
