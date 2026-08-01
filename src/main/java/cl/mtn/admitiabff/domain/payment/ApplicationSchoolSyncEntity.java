package cl.mtn.admitiabff.domain.payment;

import cl.mtn.admitiabff.domain.application.ApplicationEntity;
import cl.mtn.admitiabff.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "application_school_syncs")
public class ApplicationSchoolSyncEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false, unique = true)
    private ApplicationEntity application;

    @Column(name = "sync_status", nullable = false, length = 40)
    private String syncStatus;

    @Column(name = "business_partner_id")
    private Long businessPartnerId;

    @Column(name = "business_partner_location_id")
    private Long businessPartnerLocationId;

    @Column(name = "student_user_id")
    private Long studentUserId;

    @Column(name = "toku_customer_id")
    private String tokuCustomerId;

    @Column(name = "toku_subscription_id")
    private String tokuSubscriptionId;

    @Column(name = "guardian_state", length = 80)
    private String guardianState;

    @Column(name = "customer_state", length = 80)
    private String customerState;

    @Column(name = "student_state", length = 80)
    private String studentState;

    @Column(name = "subscription_state", length = 80)
    private String subscriptionState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings", nullable = false, columnDefinition = "jsonb")
    private String warnings = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "errors", nullable = false, columnDefinition = "jsonb")
    private String errors = "[]";

    @Column(name = "last_attempt_at", nullable = false)
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;
}
