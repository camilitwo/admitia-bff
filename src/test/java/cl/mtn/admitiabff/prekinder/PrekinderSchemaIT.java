package cl.mtn.admitiabff.prekinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Integración opt-in (Failsafe/CI): no forma parte de mvn test ni toca infraestructura real. */
@Testcontainers(disabledWithoutDocker = true)
class PrekinderSchemaIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("admitia_prekinder");

    @Test
    void cleanDatabaseBuildsOnlyPrekinderModel() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/prekinder-migration").cleanDisabled(true).load().migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var countStatement = connection.prepareStatement("""
                 SELECT count(*) FROM information_schema.tables
                  WHERE table_schema='public' AND table_name <> 'flyway_schema_history'
                 """);
             var statement = connection.prepareStatement("""
                 SELECT count(*) FROM information_schema.tables
                  WHERE table_schema='public' AND table_name IN (
                    'schema_metadata','applications','application_declarations','inclusion_record_revisions',
                    'schedule_slots','schedule_assignment_history','evaluation_assignments','evaluation_status_history',
                    'comments','comment_revisions','support_record_revisions','referral_revisions',
                    'committee_dossiers','committee_decisions','offer_status_history',
                    'restricted_case_access_grants','audit_events','outbox_events'
                  )
                 """)) {
            try (var result = countStatement.executeQuery()) { result.next(); assertEquals(52, result.getInt(1)); }
            try (var result = statement.executeQuery()) { result.next(); assertEquals(18, result.getInt(1)); }
        }


        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO audit_events(audit_id, action, result)
                VALUES ('00000000-0000-0000-0000-000000000001', 'SCHEMA_TEST', 'SUCCESS')
                """);
            assertThrows(java.sql.SQLException.class,
                () -> statement.executeUpdate("UPDATE audit_events SET result='ALTERED'"));
        }
    }
}
