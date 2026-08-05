package cl.mtn.admitiabff.prekinder.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;

@Configuration
@ConditionalOnProperty(prefix = "app.prekinder.mtls", name = "enforced", havingValue = "true")
public class PrekinderMtlsConnectorConfig {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> prekinderMtlsConnector(PrekinderProperties properties) {
        return factory -> factory.addAdditionalTomcatConnectors(connector(properties));
    }

    private Connector connector(PrekinderProperties properties) {
        var mtls = properties.mtls();
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(mtls.port());
        connector.setScheme("https");
        connector.setSecure(true);
        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
        protocol.setSSLEnabled(true);
        SSLHostConfig ssl = new SSLHostConfig();
        ssl.setSslProtocol("TLS");
        ssl.setProtocols("TLSv1.2,TLSv1.3");
        ssl.setCertificateVerification("required");
        ssl.setTruststoreFile(materialize(mtls.trustStore(), mtls.trustStoreBase64(), "prekinder-truststore", ".p12"));
        ssl.setTruststorePassword(mtls.trustStorePassword());
        ssl.setTruststoreType("PKCS12");
        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.UNDEFINED);
        certificate.setCertificateKeystoreFile(materialize(mtls.keyStore(), mtls.keyStoreBase64(), "prekinder-keystore", ".p12"));
        certificate.setCertificateKeystorePassword(mtls.keyStorePassword());
        certificate.setCertificateKeystoreType("PKCS12");
        ssl.addCertificate(certificate);
        connector.addSslHostConfig(ssl);
        return connector;
    }

    private static String materialize(String path, String base64, String prefix, String suffix) {
        if (path != null && !path.isBlank()) return path;
        if (base64 == null || base64.isBlank()) throw new IllegalStateException(prefix + " es obligatorio");
        try {
            Path file = Files.createTempFile(prefix, suffix);
            Files.write(file, Base64.getDecoder().decode(base64));
            try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { /* Windows/local no soporta POSIX */ }
            file.toFile().deleteOnExit();
            return file.toAbsolutePath().toString();
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Material mTLS inválido", exception);
        }
    }
}
