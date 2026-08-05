package cl.mtn.admitiabff.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

class LegacyDataSourceConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(LegacyDataSourceConfig.class, IsolatedPrekinderBeans.class)
        .withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://legacy.internal:5432/admitia",
            "spring.datasource.username=legacy_app",
            "spring.datasource.password=legacy-secret",
            "spring.datasource.hikari.minimum-idle=0",
            "spring.datasource.hikari.initialization-fail-timeout=-1"
        );

    @Test
    void legacyBeansRemainPrimaryWhenPrekinderBeansExist() throws Exception {
        assertThat(LegacyDataSourceConfig.class
            .getDeclaredMethod("legacyDataSource", DataSourceProperties.class)
            .isAnnotationPresent(FlywayDataSource.class)).isTrue();

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(2);
            assertThat(context.getBean(DataSource.class)).isSameAs(context.getBean("legacyDataSource"));
            assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(2);
            assertThat(context.getBean(PlatformTransactionManager.class))
                .isSameAs(context.getBean("legacyTransactionManager"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class IsolatedPrekinderBeans {
        @Bean(name = "prekinderDataSource")
        DataSource prekinderDataSource() {
            return mock(DataSource.class);
        }

        @Bean(name = "prekinderTransactionManager")
        PlatformTransactionManager prekinderTransactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        EntityManagerFactory entityManagerFactory() {
            return mock(EntityManagerFactory.class);
        }
    }
}
