package org.shelterconnect.api.database;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@Profile("!test")
@EnableConfigurationProperties(DataSourceProperties.class)
public class DatabaseConfiguration {
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties) {
        // Validate before opening any connection; startup must fail closed.
        DatabaseTargetPolicy.validate(properties.getUrl(),properties.getUsername());
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
