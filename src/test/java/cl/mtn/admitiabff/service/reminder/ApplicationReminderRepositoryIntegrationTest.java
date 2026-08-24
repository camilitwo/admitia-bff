package cl.mtn.admitiabff.service.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApplicationReminderRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static HikariDataSource dataSource;
    private static NamedParameterJdbcTemplate jdbc;
    private static ApplicationReminderRepository repository;

    @BeforeAll
    static void setUpDatabase() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new ApplicationReminderRepository(jdbc, new JdbcTransactionManager(dataSource));
        seed();
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void selectsPaymentFormAndInconsistentStatesFromPostgresql() {
        Map<Long, ApplicationReminderRepository.ApplicationState> states = repository.findActiveStates(2027)
            .stream().collect(java.util.stream.Collectors.toMap(
                ApplicationReminderRepository.ApplicationState::applicationId, state -> state));

        assertThat(ApplicationReminderService.classify(states.get(10L)).type().name())
            .isEqualTo("PAYMENT_REMINDER");
        assertThat(ApplicationReminderService.classify(states.get(11L)).type().name())
            .isEqualTo("FAMILY_REGISTRATION_REMINDER");
        assertThat(ApplicationReminderService.classify(states.get(12L)).reason())
            .isEqualTo("PAYMENT_STATUS_INCONSISTENT");
        assertThat(ApplicationReminderService.classify(states.get(13L)).type()).isNull();
        assertThat(ApplicationReminderService.classify(states.get(14L)).reason()).isEqualTo("NOT_ELIGIBLE");
        assertThat(states.get(10L).recipient()).isEqualTo("familia@example.cl");
    }

    @Test
    void uniqueSlotAndSkipLockedClaimPreventDuplicateDelivery() {
        var state = repository.findState(10L, 2027).orElseThrow();
        Instant slot = Instant.parse("2026-08-24T15:00:00Z");
        LocalDate date = LocalDate.of(2026, 8, 24);
        repository.insertDelivery(state, "PAYMENT_REMINDER", slot, date, "PENDING", null);
        repository.insertDelivery(state, "PAYMENT_REMINDER", slot, date, "PENDING", null);

        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM application_reminder_deliveries
             WHERE application_id = :id AND scheduled_slot = :slot
            """, Map.of("id", 10L, "slot", slot), Integer.class);
        assertThat(count).isEqualTo(1);

        var claimed = repository.claimNext(date, 6, 10).orElseThrow();
        assertThat(claimed.applicationId()).isEqualTo(10L);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(repository.claimNext(date, 6, 10)).isEmpty();
    }

    private static void seed() {
        jdbc.update("""
            INSERT INTO users(id, first_name, last_name, email, password_hash, role)
            VALUES (100, 'Camila', 'Pérez', 'familia@example.cl', 'x', 'GUARDIAN')
            """, Map.of());
        for (long id = 10; id <= 14; id++) {
            jdbc.update("""
                INSERT INTO students(id, first_name, paternal_last_name, grade_applied)
                VALUES (:id, 'Ana', 'Pérez', '1° Básico')
                """, Map.of("id", id));
            String status = id == 14 ? "REJECTED" : "PENDING";
            String payment = id == 11 || id == 13 ? "PAID" : "UNPAID";
            jdbc.update("""
                INSERT INTO applications(id, student_id, applicant_user_id, status, academic_year,
                    payment_required, payment_status)
                VALUES (:id, :id, 100, :status, 2027, TRUE, :payment)
                """, Map.of("id", id, "status", status, "payment", payment));
        }
        jdbc.update("""
            INSERT INTO complementary_forms(application_id, form_data, is_submitted)
            VALUES (13, '{}'::jsonb, TRUE)
            """, Map.of());
        jdbc.update("""
            INSERT INTO payments(application_id, guardian_user_id, provider, idempotency_key,
                amount, currency, status, external_status)
            VALUES (12, 100, 'TOKU', 'seed-12', 50000, 'CLP', 'PAYMENT_PENDING', 'PAGADO')
            """, Map.of());
    }
}
