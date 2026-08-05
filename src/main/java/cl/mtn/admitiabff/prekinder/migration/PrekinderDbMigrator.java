package cl.mtn.admitiabff.prekinder.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;

/** Artefacto deliberadamente independiente de Spring y de la base legacy. */
public final class PrekinderDbMigrator {
    private static final String EXPECTED_DATABASE_ID = "admitia-prekinder";
    private static final Pattern SAFE_ROLE = Pattern.compile("[a-z][a-z0-9_]{2,62}");

    private PrekinderDbMigrator() {}

    public static void main(String[] args) {
        String url = required("PREKINDER_MIGRATOR_DATASOURCE_URL");
        String migratorUser = required("PREKINDER_MIGRATOR_DATASOURCE_USERNAME");
        String migratorPassword = required("PREKINDER_MIGRATOR_DATASOURCE_PASSWORD");
        String applicationUser = required("PREKINDER_DATASOURCE_USERNAME");
        String applicationPassword = required("PREKINDER_DATASOURCE_PASSWORD");
        assertDedicatedDatabase(url, System.getenv("SPRING_DATASOURCE_URL"), required("PREKINDER_DATABASE_ID"));
        assertApplicationRole(applicationUser, applicationPassword, migratorUser);

        Flyway.configure()
            .dataSource(url, migratorUser, migratorPassword)
            .locations("classpath:db/prekinder-migration")
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .load()
            .migrate();

        provisionApplicationRole(url, migratorUser, migratorPassword, applicationUser, applicationPassword);
        verifySchemaMarker(url, migratorUser, migratorPassword);
    }

    static void assertDedicatedDatabase(String prekinderUrl, String legacyUrl, String databaseId) {
        String normalized = prekinderUrl.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("PREKINDER_MIGRATOR_DATASOURCE_URL debe ser JDBC PostgreSQL");
        }
        if (legacyUrl != null && normalized.equals(legacyUrl.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("El migrador Prekinder no puede apuntar a la base legacy");
        }
        if (!EXPECTED_DATABASE_ID.equals(databaseId)) {
            throw new IllegalStateException("PREKINDER_DATABASE_ID no identifica la base dedicada esperada");
        }
    }

    static void assertApplicationRole(String applicationUser, String applicationPassword, String migratorUser) {
        if (!SAFE_ROLE.matcher(applicationUser).matches()) {
            throw new IllegalArgumentException("PREKINDER_DATASOURCE_USERNAME tiene formato inválido");
        }
        if (applicationUser.equals(migratorUser)) {
            throw new IllegalStateException("Los usuarios migrador y de aplicación deben ser diferentes");
        }
        if (applicationPassword.length() < 24) {
            throw new IllegalArgumentException("PREKINDER_DATASOURCE_PASSWORD debe tener al menos 24 caracteres");
        }
    }

    private static void provisionApplicationRole(String url, String migratorUser, String migratorPassword,
                                                 String applicationUser, String applicationPassword) {
        try (Connection connection = DriverManager.getConnection(url, migratorUser, migratorPassword)) {
            connection.setAutoCommit(false);
            String role = quoteIdentifier(connection, applicationUser);
            String password = quoteLiteral(connection, applicationPassword);
            execute(connection, roleExists(connection, applicationUser)
                ? "ALTER ROLE " + role + " LOGIN PASSWORD " + password
                : "CREATE ROLE " + role + " LOGIN PASSWORD " + password);
            execute(connection, "ALTER ROLE " + role + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION");
            execute(connection, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            execute(connection, "REVOKE CREATE ON SCHEMA public FROM " + role);
            execute(connection, "GRANT CONNECT ON DATABASE " + quoteIdentifier(connection, connection.getCatalog()) + " TO " + role);
            execute(connection, "GRANT USAGE ON SCHEMA public TO " + role);
            execute(connection, "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + role);
            execute(connection, "GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO " + role);
            execute(connection, "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + role);
            execute(connection, "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO " + role);
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("No fue posible provisionar el rol runtime de Prekinder", exception);
        }
    }

    private static void verifySchemaMarker(String url, String username, String password) {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT count(*) FROM schema_metadata WHERE database_key = ?")) {
            statement.setString(1, EXPECTED_DATABASE_ID);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new IllegalStateException("La base no contiene el marcador de esquema Prekinder");
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("No fue posible verificar el esquema Prekinder", exception);
        }
    }

    private static boolean roleExists(Connection connection, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
            statement.setString(1, role);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static String quoteIdentifier(Connection connection, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT quote_ident(?)")) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getString(1); }
        }
    }

    private static String quoteLiteral(Connection connection, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT quote_literal(?)")) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getString(1); }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " es obligatorio");
        }
        return value.trim();
    }
}
