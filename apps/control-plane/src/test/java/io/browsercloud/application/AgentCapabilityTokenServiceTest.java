package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.RiskClass.R1_LOW_RISK_CHANGE;
import static io.browsercloud.domain.agent.AgentModels.ToolId.NAVIGATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentCapabilityTokenServiceTest {

  private final AgentCapabilityTokenService service =
      new AgentCapabilityTokenService(
          new ObjectMapper().findAndRegisterModules(),
          "test-agent-capability-token-secret-with-more-than-32-bytes",
          "test");

  @Test
  void bindsTokenToTenantSessionIntentToolDomainAndExpiry() {
    var expiresAt = Instant.parse("2026-07-26T01:00:00Z");
    var issued =
        service.issue(
            "tenant-a",
            "ses_1234567890abcdef",
            "int_1234567890abcdef",
            "agt_1234567890abcdef",
            NAVIGATE,
            "example.com",
            "NAVIGATION",
            R1_LOW_RISK_CHANGE,
            expiresAt);

    var claims =
        service.verify(
            issued.token(),
            "tenant-a",
            "ses_1234567890abcdef",
            "int_1234567890abcdef",
            "agt_1234567890abcdef",
            NAVIGATE,
            "example.com",
            "NAVIGATION",
            Instant.parse("2026-07-26T00:59:00Z"));

    assertThat(claims.tokenId()).isEqualTo(issued.tokenId());
    assertThat(claims.maxCalls()).isEqualTo(1);
  }

  @Test
  void rejectsDomainExpansionAndTampering() {
    var issued =
        service.issue(
            "tenant-a",
            "ses_1234567890abcdef",
            "int_1234567890abcdef",
            "agt_1234567890abcdef",
            NAVIGATE,
            "example.com",
            "NAVIGATION",
            R1_LOW_RISK_CHANGE,
            Instant.parse("2026-07-26T01:00:00Z"));

    assertThatThrownBy(
            () ->
                service.verify(
                    issued.token(),
                    "tenant-a",
                    "ses_1234567890abcdef",
                    "int_1234567890abcdef",
                    "agt_1234567890abcdef",
                    NAVIGATE,
                    "evil.example",
                    "NAVIGATION",
                    Instant.parse("2026-07-26T00:59:00Z")))
        .isInstanceOf(AgentCapabilityTokenService.InvalidCapabilityTokenException.class);

    assertThatThrownBy(
            () ->
                service.verify(
                    issued.token() + "x",
                    "tenant-a",
                    "ses_1234567890abcdef",
                    "int_1234567890abcdef",
                    "agt_1234567890abcdef",
                    NAVIGATE,
                    "example.com",
                    "NAVIGATION",
                    Instant.parse("2026-07-26T00:59:00Z")))
        .isInstanceOf(AgentCapabilityTokenService.InvalidCapabilityTokenException.class);
  }
}
