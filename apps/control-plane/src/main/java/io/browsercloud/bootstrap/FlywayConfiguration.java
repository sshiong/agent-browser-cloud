package io.browsercloud.bootstrap;

import java.util.Map;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayConfiguration {

  @Bean
  FlywayConfigurationCustomizer postgresqlSessionLevelMigrationLock() {
    return configuration ->
        configuration.configuration(
            Map.of("flyway.postgresql.transactional.lock", Boolean.FALSE.toString()));
  }
}
