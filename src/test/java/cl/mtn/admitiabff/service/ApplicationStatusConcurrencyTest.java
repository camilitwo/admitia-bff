package cl.mtn.admitiabff.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cl.mtn.admitiabff.domain.common.ApplicationStatus;
import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.repository.ApplicationRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApplicationStatusConcurrencyTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static HikariDataSource source;
    static LocalContainerEntityManagerFactoryBean factory;
    static JdbcTemplate jdbc;
    static TransactionTemplate transactions;
    static ApplicationRepository applications;
    static ApplicationStatusTransitionService transitions;

    @BeforeAll
    static void setup() {
        source = new HikariDataSource();
        source.setJdbcUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan("cl.mtn.admitiabff.domain");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        var emf = factory.getObject();
        transactions = new TransactionTemplate(new JpaTransactionManager(emf));
        applications = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
            .getRepository(ApplicationRepository.class);
        jdbc = new JdbcTemplate(source);
        // Campos del modelo actual ausentes en las migraciones históricas del esquema general.
        // Se completan solo en la base efímera para probar las transacciones JPA existentes.
        jdbc.execute("ALTER TABLE students ADD COLUMN gender VARCHAR(255), ADD COLUMN has_siblings_in_school BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN siblings_in_school_details TEXT");
        var auth = mock(AuthService.class);
        var actor = new AuthService.AuthContextHolder(9001L, "admin@example.cl", "ADMIN");
        when(auth.requireAuth()).thenReturn(actor);
        when(auth.hasAnyRoleContext(actor, Role.ADMIN, Role.COORDINATOR)).thenReturn(true);
        transitions = new ApplicationStatusTransitionService(auth, jdbc);
        jdbc.update("INSERT INTO users(id, first_name, last_name, email, password_hash, role) VALUES (9001,'Admin','Test','admin@example.cl','x','ADMIN')");
        jdbc.update("INSERT INTO families(id) VALUES (9001)");
        jdbc.update("INSERT INTO students(id, first_name, paternal_last_name, grade_applied) VALUES (9001,'Ana','Test','KINDER')");
        jdbc.update("INSERT INTO applications(id, family_id, student_id, status, submission_date) VALUES (9001,9001,9001,'WAITLIST',now())");
    }

    @AfterAll
    static void close() {
        if (factory != null) factory.destroy();
        if (source != null) source.close();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM application_status_history");
        jdbc.update("UPDATE applications SET status = 'WAITLIST' WHERE id = 9001");
    }

    @Test
    void conflictingConcurrentDecisionsOnlyCommitOneResultAndOneAudit() throws Exception {
        var firstLocked = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transactions.execute(status -> {
                var application = applications.findActiveByIdForUpdate(9001L).orElseThrow();
                firstLocked.countDown();
                await(releaseFirst);
                transitions.transition(application, ApplicationStatus.APPROVED);
                return true;
            }));
            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> transactions.execute(status -> {
                secondStarted.countDown();
                var application = applications.findActiveByIdForUpdate(9001L).orElseThrow();
                transitions.transition(application, ApplicationStatus.REJECTED);
                return true;
            }));
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            releaseFirst.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS));
            var failure = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ApplicationStatusPolicy.StatusTransitionException.class, failure.getCause());
        } finally {
            releaseFirst.countDown();
        }
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM applications WHERE id=9001", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM application_status_history", Integer.class));
        assertEquals(9001L, jdbc.queryForObject("SELECT actor_id FROM application_status_history", Long.class));
    }

    @Test
    void staleNonStatusEditCannotOverwriteAConcurrentFinalDecision() throws Exception {
        var loaded = new CountDownLatch(1);
        var decisionCommitted = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var notesUpdate = executor.submit(() -> transactions.execute(status -> {
                var application = applications.findActiveById(9001L).orElseThrow();
                loaded.countDown();
                await(decisionCommitted);
                application.setNotes("Documento verificado");
                return true;
            }));
            assertTrue(loaded.await(10, TimeUnit.SECONDS));
            try {
                transactions.execute(status -> {
                    var application = applications.findActiveByIdForUpdate(9001L).orElseThrow();
                    return transitions.transition(application, ApplicationStatus.APPROVED);
                });
            } finally {
                decisionCommitted.countDown();
            }
            assertTrue(notesUpdate.get(10, TimeUnit.SECONDS));
        }
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM applications WHERE id=9001", String.class));
        assertEquals("Documento verificado", jdbc.queryForObject("SELECT notes FROM applications WHERE id=9001", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM application_status_history", Integer.class));
    }

    @Test
    void rollbackAlsoRollsBackHistory() {
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            var application = applications.findActiveByIdForUpdate(9001L).orElseThrow();
            transitions.transition(application, ApplicationStatus.REJECTED);
            throw new IllegalStateException("Fallo posterior");
        }));
        assertEquals("WAITLIST", jdbc.queryForObject("SELECT status FROM applications WHERE id=9001", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM application_status_history", Integer.class));
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout de prueba");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
