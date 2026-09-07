package io.browsercloud.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.application.AgentActionPayloadService;
import io.browsercloud.application.AgentCapabilityTokenService;
import io.browsercloud.infrastructure.GrpcTransportFactory;
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

  @Test
  void nonLocalEnvironmentsCannotKeepPlaintextTransportOrKnownAgentSecrets() {
    for (var environment :
        new String[] {"production", "staging", "prod", "LOCAL", "", "test ", null}) {
      assertThatThrownBy(() -> new GrpcTransportFactory(environment, false, "", "", "", "node"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("mTLS");
      assertThatThrownBy(
              () ->
                  new AgentActionPayloadService(
                      "browsercloud-local-agent-action-payload-secret-v1", environment))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must be configured");
      assertThatThrownBy(
              () ->
                  new AgentCapabilityTokenService(
                      new ObjectMapper(),
                      "browsercloud-local-agent-capability-token-secret-v1",
                      environment))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("must be configured");
    }
  }

  @Test
  void explicitDevelopmentEnvironmentsRetainLocalTransport() {
    for (var environment : new String[] {"local", "test"}) {
      assertThat(new GrpcTransportFactory(environment, false, "", "", "", "node").tlsEnabled())
          .isFalse();
      new AgentActionPayloadService(
          "browsercloud-local-agent-action-payload-secret-v1", environment);
      new AgentCapabilityTokenService(
          new ObjectMapper(), "browsercloud-local-agent-capability-token-secret-v1", environment);
    }
  }
}
