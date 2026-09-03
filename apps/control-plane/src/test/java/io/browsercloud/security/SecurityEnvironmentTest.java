package io.browsercloud.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class SecurityEnvironmentTest {
  private final WebApplicationContextRunner context =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
          .withUserConfiguration(SecurityConfiguration.class);

  @Test
  void onlyExplicitDevelopmentEnvironmentsEnableLocalHeaders() {
    for (var environment : new String[] {"local", "test"}) {
      context
          .withPropertyValues("app.environment=" + environment)
          .run(
              application -> {
                assertThat(application).hasNotFailed();
                assertThat(application).hasBean("localSecurityFilterChain");
                assertThat(application).doesNotHaveBean("productionSecurityFilterChain");
              });
    }
  }

  @Test
  void nonLocalEnvironmentsFailClosedWithoutOidcConfiguration() {
    for (var environment : new String[] {"production", "staging", "prod", "LOCAL", ""}) {
      context
          .withPropertyValues("app.environment=" + environment)
          .run(application -> assertThat(application).hasFailed());
    }
  }
}
