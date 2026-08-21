package cl.mtn.admitiabff.prekinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.DriverManager;
import java.sql.Types;
import java.util.UUID;
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
                    'restricted_case_access_grants','audit_events','outbox_events',
                    'prekinder_payments','prekinder_payment_events',
                    'prekinder_application_school_syncs','prekinder_complementary_forms'
                  )
                 """)) {
            try (var result = countStatement.executeQuery()) { result.next(); assertEquals(69, result.getInt(1)); }
            try (var result = statement.executeQuery()) { result.next(); assertEquals(22, result.getInt(1)); }
        }


        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO actors(actor_id, external_subject, role_code)
                VALUES ('00000000-0000-0000-0000-000000000010', 'legacy:42', 'APODERADO')
                ON CONFLICT (external_subject) DO UPDATE SET updated_at = now()
                """);
            statement.executeUpdate("""
                INSERT INTO actors(actor_id, external_subject, role_code)
                VALUES ('00000000-0000-0000-0000-000000000011', 'legacy:42', 'APODERADO')
                ON CONFLICT (external_subject) DO UPDATE SET updated_at = now()
                """);
            try (var actorCount = statement.executeQuery(
                    "SELECT count(*) FROM actors WHERE external_subject = 'legacy:42'")) {
                actorCount.next();
                assertEquals(1, actorCount.getInt(1));
            }

            statement.executeUpdate("""
                INSERT INTO audit_events(audit_id, action, result)
                VALUES ('00000000-0000-0000-0000-000000000001', 'SCHEMA_TEST', 'SUCCESS')
                """);
            assertThrows(java.sql.SQLException.class,
                () -> statement.executeUpdate("UPDATE audit_events SET result='ALTERED'"));
        }

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                 SELECT DISTINCT i.instrument_code, i.display_name, i.capture_mode, i.sensitive, i.active, i.position
                   FROM evaluation_instruments i
                  WHERE i.active = true
                    AND (
                         EXISTS (
                             SELECT 1 FROM professional_instrument_authorizations authz
                              WHERE authz.professional_id = ?
                                AND authz.instrument_code = i.instrument_code
                                AND (CAST(? AS uuid) IS NULL OR authz.process_id = CAST(? AS uuid))
                         )
                         OR EXISTS (
                             SELECT 1 FROM group_instrument_assignments assignment
                             JOIN evaluation_groups assigned_group ON assigned_group.group_id = assignment.group_id
                              WHERE assignment.evaluator_id = ?
                                AND assignment.instrument_code = i.instrument_code
                                AND (CAST(? AS uuid) IS NULL OR assigned_group.process_id = CAST(? AS uuid))
                         )
                    )
                  ORDER BY i.position
                 """)) {
            UUID actorId = UUID.randomUUID();
            statement.setObject(1, actorId);
            statement.setNull(2, Types.OTHER);
            statement.setNull(3, Types.OTHER);
            statement.setObject(4, actorId);
            statement.setNull(5, Types.OTHER);
            statement.setNull(6, Types.OTHER);
            statement.executeQuery().close();
        }
    }
}
