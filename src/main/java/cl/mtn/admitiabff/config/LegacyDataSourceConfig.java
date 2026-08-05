package cl.mtn.admitiabff.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Mantiene JPA, Flyway y @Transactional legacy aislados del datasource Prekinder. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataSourceProperties.class)
public class LegacyDataSourceConfig {

    @Bean(name = {"dataSource", "legacyDataSource"}, destroyMethod = "close")
    @Primary
    @FlywayDataSource
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource legacyDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
            .type(HikariDataSource.class)
            .build();
    }

    @Bean(name = {"transactionManager", "legacyTransactionManager"})
    @Primary
    PlatformTransactionManager legacyTransactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
