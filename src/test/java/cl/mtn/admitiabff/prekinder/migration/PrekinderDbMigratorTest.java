package cl.mtn.admitiabff.prekinder.migration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PrekinderDbMigratorTest {
    @Test
    void acceptsOnlyDedicatedPrekinderDatabase() {
        assertDoesNotThrow(() -> PrekinderDbMigrator.assertDedicatedDatabase(
            "jdbc:postgresql://private:5432/railway", "jdbc:postgresql://legacy:5432/railway", "admitia-prekinder"
        ));
    }

    @Test
    void refusesLegacyDatabase() {
        assertThrows(IllegalStateException.class, () -> PrekinderDbMigrator.assertDedicatedDatabase(
            "jdbc:postgresql://private:5432/railway", "jdbc:postgresql://private:5432/railway", "admitia-prekinder"
        ));
    }

    @Test
    void refusesIncorrectDatabaseIdentifier() {
        assertThrows(IllegalStateException.class, () -> PrekinderDbMigrator.assertDedicatedDatabase(
            "jdbc:postgresql://private:5432/railway", null, "legacy"
        ));
    }

    @Test
    void requiresSeparateStrongApplicationCredentials() {
        assertDoesNotThrow(() -> PrekinderDbMigrator.assertApplicationRole(
            "prekinder_app", "a-secure-password-with-32-chars", "postgres"
        ));
        assertThrows(IllegalStateException.class, () -> PrekinderDbMigrator.assertApplicationRole(
            "postgres", "a-secure-password-with-32-chars", "postgres"
        ));
        assertThrows(IllegalArgumentException.class, () -> PrekinderDbMigrator.assertApplicationRole(
            "prekinder-app", "short", "postgres"
        ));
    }
}
