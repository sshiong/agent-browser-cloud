package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AgentActionPayloadServiceTest {

  private final AgentActionPayloadService service =
      new AgentActionPayloadService(
          "test-agent-action-payload-secret-with-more-than-32-bytes", "test");

  @Test
  void sealsWithBoundAdditionalAuthenticatedData() {
    var sealed =
        service.seal("tenant-test", "agt_1234567890abcdef", "step_1234567890abcd", "public note");

    assertThat(sealed).startsWith("v1.").doesNotContain("public note");
    assertThat(service.unseal("tenant-test", "agt_1234567890abcdef", "step_1234567890abcd", sealed))
        .isEqualTo("public note");
    assertThatThrownBy(
            () ->
                service.unseal(
                    "tenant-other", "agt_1234567890abcdef", "step_1234567890abcd", sealed))
        .isInstanceOf(AgentActionPayloadService.InvalidActionPayloadException.class);
  }

  @Test
  void rejectsLocalDefaultSecretInProduction() {
    assertThatThrownBy(
            () ->
                new AgentActionPayloadService(
                    "browsercloud-local-agent-action-payload-secret-v1", "production"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be configured");
  }
}
