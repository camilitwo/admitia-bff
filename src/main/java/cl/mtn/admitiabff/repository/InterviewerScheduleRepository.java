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
    @Query("select s from InterviewerScheduleEntity s where s.active = true and s.interviewer.id = :interviewerId and s.year = :year and ((s.specificDate = :date) or (s.specificDate is null and s.dayOfWeek = :dayOfWeek))")
    List<InterviewerScheduleEntity> findAvailableTemplates(@Param("interviewerId") Long interviewerId, @Param("date") LocalDate date, @Param("dayOfWeek") String dayOfWeek, @Param("year") Integer year);
    @Query("select count(s) > 0 from InterviewerScheduleEntity s where s.interviewer.id = :interviewerId and coalesce(s.dayOfWeek, '') = coalesce(:dayOfWeek, '') and s.startTime = :startTime and s.endTime = :endTime and s.year = :year and ((s.specificDate is null and :specificDate is null) or s.specificDate = :specificDate)")
    boolean existsDuplicate(@Param("interviewerId") Long interviewerId, @Param("dayOfWeek") String dayOfWeek, @Param("startTime") LocalTime startTime, @Param("endTime") LocalTime endTime, @Param("year") Integer year, @Param("specificDate") LocalDate specificDate);
    Optional<InterviewerScheduleEntity> findByInterviewerIdAndSpecificDateAndStartTimeAndEndTimeAndYear(Long interviewerId, LocalDate specificDate, LocalTime startTime, LocalTime endTime, Integer year);
    @Query(value = """
        SELECT
            u.id AS interviewerId,
            u.first_name AS firstName,
            u.last_name AS lastName,
            u.email AS email,
            u.role AS role,
            u.subject AS subject,
            (
                SELECT COUNT(*)
                FROM interviewer_schedules s
                WHERE s.interviewer_id = u.id
                  AND s.is_active = true
            ) AS scheduleCount
        FROM users u
        WHERE u.role IN ('PSYCHOLOGIST', 'CYCLE_DIRECTOR', 'COORDINATOR', 'INTERVIEWER')
          AND u.active = true
        ORDER BY u.last_name, u.first_name
        """, nativeQuery = true)
    List<InterviewerWithCountView> findInterviewersWithSchedules(@Param("year") Integer year);

    @Query(value = """
        SELECT
            u.id AS interviewerId,
            CONCAT(u.first_name, ' ', u.last_name) AS name,
            u.role AS role,
            u.subject AS subject,
            CASE
                WHEN u.role IN ('CYCLE_DIRECTOR', 'PSYCHOLOGIST', 'INTERVIEWER') THEN 'ALL'
                WHEN u.subject LIKE '%MATH%' OR u.subject LIKE '%SCIENCE%' THEN 'SECONDARY'
                ELSE 'PRIMARY'
            END AS educationalLevel,
            (
                SELECT COUNT(*)
                FROM interviewer_schedules s
                WHERE s.interviewer_id = u.id
                  AND s.is_active = true
            ) AS scheduleCount
        FROM users u
        WHERE u.role IN ('TEACHER', 'PSYCHOLOGIST', 'CYCLE_DIRECTOR', 'COORDINATOR', 'INTERVIEWER')
          AND u.active = true
        ORDER BY u.role, u.last_name, u.first_name
        """, nativeQuery = true)
    List<PublicInterviewerView> findPublicInterviewers();

    interface InterviewerWithCountView {
        Long getInterviewerId();
        String getFirstName();
        String getLastName();
        String getEmail();
        Object getRole();
        String getSubject();
        long getScheduleCount();
    }

    interface PublicInterviewerView {
        Long getInterviewerId();
        String getName();
        Object getRole();
        String getSubject();
        String getEducationalLevel();
        long getScheduleCount();
    }
}
