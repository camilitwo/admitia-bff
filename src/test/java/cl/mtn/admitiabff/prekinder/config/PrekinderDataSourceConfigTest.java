package cl.mtn.admitiabff.prekinder.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrekinderDataSourceConfigTest {
    @Test
    void connectionsCommitSingleStatementWritesOutsideExplicitTransactions() {
        var properties = new PrekinderProperties(true,
            new PrekinderProperties.Datasource("jdbc:postgresql://localhost:5433/prekinder", "app", "secret",
                "org.postgresql.Driver", new PrekinderProperties.Hikari("test", 2, 0, 3000, 2000, 0)),
            new PrekinderProperties.Encryption("V1", "unused"),
            new PrekinderProperties.Realtime(List.of("http://localhost"), Duration.ofSeconds(30),
                Duration.ofSeconds(20), 1, 1024, 10, Duration.ofSeconds(10)),
            new PrekinderProperties.Mtls(false, 8443, "", "", "", "", "", ""));

        assertThat(PrekinderDataSourceConfig.hikariConfig(properties).isAutoCommit()).isTrue();
    }
}
