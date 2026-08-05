package cl.mtn.admitiabff.prekinder.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.prekinder")
public record PrekinderProperties(
    boolean enabled,
    Datasource datasource,
    Encryption encryption,
    Realtime realtime,
    Mtls mtls
) {
    public record Datasource(
        String url,
        String username,
        String password,
        String driverClassName,
        Hikari hikari
    ) {}

    public record Hikari(
        String poolName,
        int maximumPoolSize,
        int minimumIdle,
        long connectionTimeout,
        long validationTimeout,
        long leakDetectionThreshold
    ) {}

    public record Encryption(String activeVersion, String keyV1) {}

    public record Realtime(
        List<String> allowedOrigins,
        Duration ticketTtl,
        Duration heartbeat,
        int maxConnectionsPerUser,
        int maxFrameBytes,
        int operationsPerWindow,
        Duration rateWindow
    ) {}

    public record Mtls(
        boolean enforced,
        int port,
        String keyStore,
        String keyStoreBase64,
        String keyStorePassword,
        String trustStore,
        String trustStoreBase64,
        String trustStorePassword
    ) {}
}
