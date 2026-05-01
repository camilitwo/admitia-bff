package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.common.InterviewStatus;
import cl.mtn.admitiabff.domain.interview.InterviewEntity;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewRepository extends JpaRepository<InterviewEntity, Long> {
    List<InterviewEntity> findAllByOrderByCreatedAtDesc();
    List<InterviewEntity> findByApplicationIdOrderByScheduledDateDesc(Long applicationId);
    long countByApplicationIdAndSummarySentTrue(Long applicationId);
    @Query("select i from InterviewEntity i where (i.interviewer.id = :interviewerId or i.secondInterviewer.id = :interviewerId) and i.status not in :excluded order by i.scheduledDate, i.scheduledTime")
    List<InterviewEntity> findVisibleForInterviewer(@Param("interviewerId") Long interviewerId, @Param("excluded") List<InterviewStatus> excluded);
    List<InterviewEntity> findByInterviewerIdAndScheduledDateAndStatusIn(Long interviewerId, LocalDate date, List<InterviewStatus> statuses);
    @Query("select i from InterviewEntity i where (i.interviewer.id = :interviewerId or i.secondInterviewer.id = :interviewerId) and i.scheduledDate = :date and i.status not in :excluded")
    List<InterviewEntity> findBlockingForInterviewer(@Param("interviewerId") Long interviewerId, @Param("date") LocalDate date, @Param("excluded") List<InterviewStatus> excluded);
    long countByStatus(InterviewStatus status);
    long countByScheduledDateGreaterThanEqualAndStatus(LocalDate date, InterviewStatus status);
    @Query("select i.status as key, count(i) as total from InterviewEntity i group by i.status")
    List<KeyCountView> countByStatus();
    @Query("select i.interviewType as key, count(i) as total from InterviewEntity i group by i.interviewType")
    List<KeyCountView> countByType();
    List<InterviewEntity> findByScheduledDateGreaterThanEqualAndScheduledDateLessThanEqualOrderByScheduledDateAscScheduledTimeAsc(LocalDate startDate, LocalDate endDate);
    List<InterviewEntity> findByScheduledDateGreaterThanEqualOrderByScheduledDateAscScheduledTimeAsc(LocalDate startDate);
    List<InterviewEntity> findByScheduledDateLessThanEqualOrderByScheduledDateAscScheduledTimeAsc(LocalDate endDate);
    List<InterviewEntity> findAllByOrderByScheduledDateAscScheduledTimeAsc();

    default List<InterviewEntity> findForCalendar(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null)
            return findByScheduledDateGreaterThanEqualAndScheduledDateLessThanEqualOrderByScheduledDateAscScheduledTimeAsc(startDate, endDate);
        if (startDate != null)
            return findByScheduledDateGreaterThanEqualOrderByScheduledDateAscScheduledTimeAsc(startDate);
        if (endDate != null)
            return findByScheduledDateLessThanEqualOrderByScheduledDateAscScheduledTimeAsc(endDate);
        return findAllByOrderByScheduledDateAscScheduledTimeAsc();
    }

    interface KeyCountView {
        String getKey();
        long getTotal();
    }
}
