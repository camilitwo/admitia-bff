package cl.mtn.admitiabff.prekinder.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PrekinderProperties.class)
public class PrekinderDataSourceConfig {

    @Bean(name = "prekinderDataSource", destroyMethod = "close")
    DataSource prekinderDataSource(PrekinderProperties properties) {
        var source = properties.datasource();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(source.url());
        config.setUsername(source.username());
        config.setPassword(source.password());
        config.setDriverClassName(source.driverClassName());
        config.setPoolName(source.hikari().poolName());
        config.setMaximumPoolSize(source.hikari().maximumPoolSize());
        config.setMinimumIdle(source.hikari().minimumIdle());
        config.setConnectionTimeout(source.hikari().connectionTimeout());
        config.setValidationTimeout(source.hikari().validationTimeout());
        config.setLeakDetectionThreshold(source.hikari().leakDetectionThreshold());
        config.setAutoCommit(false);
        return new HikariDataSource(config);
    }

    @Bean(name = "prekinderJdbc")
    NamedParameterJdbcTemplate prekinderJdbc(@Qualifier("prekinderDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean(name = "prekinderTransactionManager")
    PlatformTransactionManager prekinderTransactionManager(
        @Qualifier("prekinderDataSource") DataSource dataSource
    ) {
        return new JdbcTransactionManager(dataSource);
    }
}
