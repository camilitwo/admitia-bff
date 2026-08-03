package cl.mtn.admitiabff.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AdmissionCycleMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("admitia_test")
            .withUsername("admitia")
            .withPassword("admitia");

    @Test
    void v20IsAdditiveAndDoesNotRewriteExistingAcademicYear() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("19"))
                .load()
                .migrate();

        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO students (first_name) VALUES ('Alumno')
                    """);
            statement.executeUpdate("""
                    INSERT INTO guardians (full_name, email) VALUES ('Apoderado', 'apoderado@example.cl')
                    """);
            statement.executeUpdate("""
                    INSERT INTO applications (student_id, guardian_id, status, academic_year)
                    VALUES ((SELECT max(id) FROM students), (SELECT max(id) FROM guardians), 'APPROVED', 2026)
                    """);
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT academic_year FROM applications")) {
                assertTrue(result.next());
                assertEquals(2026, result.getInt(1), "V20 no debe corregir datos existentes automáticamente");
            }
            try (ResultSet result = statement.executeQuery("SELECT status FROM admission_cycles WHERE academic_year = 2027")) {
                assertTrue(result.next());
                assertEquals("OPEN", result.getString(1));
            }
            try (ResultSet result = statement.executeQuery("SELECT count(*) FROM admission_result_dispatches")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1), "La migración no debe encolar ni enviar resultados");
            }

            statement.executeUpdate("""
                    INSERT INTO applications (student_id, guardian_id, status, academic_year)
                    SELECT (SELECT max(id) FROM students), (SELECT max(id) FROM guardians), 'APPROVED', 2027
                    FROM generate_series(1, 1000)
                    """);
            int queued = statement.executeUpdate("""
                    INSERT INTO admission_result_dispatches (
                        cycle_id, application_id, recipient_email, recipient_name, status,
                        attempts, next_attempt_at, idempotency_key, created_at, updated_at
                    )
                    SELECT c.id, a.id, lower(trim(g.email)), trim(g.full_name), 'PENDING',
                           0, CURRENT_TIMESTAMP,
                           'admission-result/' || c.academic_year || '/' || a.id,
                           CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    FROM applications a
                    JOIN guardians g ON g.id = a.guardian_id
                    JOIN admission_cycles c ON c.academic_year = a.academic_year
                    WHERE c.academic_year = 2027
                      AND a.deleted_at IS NULL
                      AND a.is_archived = false
                      AND a.status IN ('APPROVED', 'WAITLIST', 'REJECTED')
                    ON CONFLICT (cycle_id, application_id) DO NOTHING
                    """);
            assertEquals(1000, queued, "Un único INSERT ... SELECT debe encolar el ciclo completo");

            int duplicateQueue = statement.executeUpdate("""
                    INSERT INTO admission_result_dispatches (
                        cycle_id, application_id, recipient_email, recipient_name, status,
                        attempts, next_attempt_at, idempotency_key, created_at, updated_at
                    )
                    SELECT c.id, a.id, lower(trim(g.email)), trim(g.full_name), 'PENDING',
                           0, CURRENT_TIMESTAMP,
                           'admission-result/' || c.academic_year || '/' || a.id,
                           CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    FROM applications a
                    JOIN guardians g ON g.id = a.guardian_id
                    JOIN admission_cycles c ON c.academic_year = a.academic_year
                    WHERE c.academic_year = 2027
                    ON CONFLICT (cycle_id, application_id) DO NOTHING
                    """);
            assertEquals(0, duplicateQueue, "Repetir el cierre no puede duplicar la cola");
        }
    }
}
