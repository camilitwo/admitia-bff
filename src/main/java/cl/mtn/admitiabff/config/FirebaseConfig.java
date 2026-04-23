package cl.mtn.admitiabff.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${app.firebase.service-account-json:}")
    private String serviceAccountJson;

    @PostConstruct
    public void init() throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            log.info("[Firebase] Already initialized");
            return;
        }

        InputStream serviceAccount;

        if (serviceAccountJson != null && !serviceAccountJson.isBlank()) {
            // Production: JSON from environment variable (Railway)
            log.info("[Firebase] Initializing from FIREBASE_SERVICE_ACCOUNT_JSON env var");
            serviceAccount = new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8));
        } else {
            // Development: from classpath file
            log.info("[Firebase] Initializing from classpath firebase-service-account.json");
            serviceAccount = getClass().getResourceAsStream("/firebase-service-account.json");
            if (serviceAccount == null) {
                log.warn("[Firebase] No service account found — Firebase Auth will not be available. "
                    + "Place firebase-service-account.json in src/main/resources/ or set FIREBASE_SERVICE_ACCOUNT_JSON env var.");
                return;
            }
        }

        FirebaseOptions options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAccount))
            .build();
        FirebaseApp.initializeApp(options);
        log.info("[Firebase] Initialized successfully");
    }
}
