package cl.mtn.admitiabff.domain.interview;

import cl.mtn.admitiabff.domain.common.BaseEntity;
import cl.mtn.admitiabff.domain.user.UserEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "interviewer_pairs")
public class InterviewerPairEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_director_id", nullable = false)
    private UserEntity cycleDirector;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "psychologist_id", nullable = false)
    private UserEntity psychologist;

    @Column(nullable = false)
    private Integer revision = 1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_pair_id")
    private InterviewerPairEntity supersedesPair;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "interviewer_pair_grades", joinColumns = @JoinColumn(name = "pair_id"))
    @Column(name = "grade_code", nullable = false)
    private Set<String> grades = new LinkedHashSet<>();
}
