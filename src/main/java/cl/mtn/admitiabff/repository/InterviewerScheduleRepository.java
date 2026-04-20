package cl.mtn.admitiabff.repository;

import cl.mtn.admitiabff.domain.interview.InterviewerScheduleEntity;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewerScheduleRepository extends JpaRepository<InterviewerScheduleEntity, Long> {
    List<InterviewerScheduleEntity> findByInterviewerIdOrderByYearDescDayOfWeekAscStartTimeAsc(Long interviewerId);
    List<InterviewerScheduleEntity> findByInterviewerIdAndYearOrderByDayOfWeekAscStartTimeAsc(Long interviewerId, Integer year);
    @Query("select s from InterviewerScheduleEntity s where s.active = true and s.interviewer.id = :interviewerId and ((s.specificDate = :date) or (s.specificDate is null and s.dayOfWeek = :dayOfWeek))")
    List<InterviewerScheduleEntity> findAvailableTemplates(@Param("interviewerId") Long interviewerId, @Param("date") LocalDate date, @Param("dayOfWeek") Integer dayOfWeek);
    @Query("select count(s) > 0 from InterviewerScheduleEntity s where s.interviewer.id = :interviewerId and coalesce(s.dayOfWeek, -1) = coalesce(:dayOfWeek, -1) and s.startTime = :startTime and s.endTime = :endTime and s.year = :year and ((s.specificDate is null and :specificDate is null) or s.specificDate = :specificDate)")
    boolean existsDuplicate(@Param("interviewerId") Long interviewerId, @Param("dayOfWeek") Integer dayOfWeek, @Param("startTime") LocalTime startTime, @Param("endTime") LocalTime endTime, @Param("year") Integer year, @Param("specificDate") LocalDate specificDate);
    Optional<InterviewerScheduleEntity> findByInterviewerIdAndSpecificDateAndStartTimeAndEndTimeAndYear(Long interviewerId, LocalDate specificDate, LocalTime startTime, LocalTime endTime, Integer year);
    @Query("select s.interviewer.id as interviewerId, s.interviewer.firstName as firstName, s.interviewer.lastName as lastName, s.interviewer.email as email, s.interviewer.role as role, s.interviewer.subject as subject, count(s) as scheduleCount from InterviewerScheduleEntity s where s.active = true and s.year = :year group by s.interviewer.id, s.interviewer.firstName, s.interviewer.lastName, s.interviewer.email, s.interviewer.role, s.interviewer.subject order by s.interviewer.firstName, s.interviewer.lastName")
    List<InterviewerWithCountView> findInterviewersWithSchedules(@Param("year") Integer year);

    interface InterviewerWithCountView {
        Long getInterviewerId();
        String getFirstName();
        String getLastName();
        String getEmail();
        Object getRole();
        String getSubject();
        long getScheduleCount();
    }
}
