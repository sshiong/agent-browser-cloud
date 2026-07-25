package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.ToolCapabilityUseJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentReadToolServiceTest {

  @Mock private BrowserStateRepository stateRepository;
  @Mock private ToolCapabilityUseJpaRepository capabilityUses;

  private AgentReadToolService service;
  private AgentCapabilityTokenService tokenService;

  @BeforeEach
  void setUp() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    tokenService =
        new AgentCapabilityTokenService(
            mapper, "test-agent-capability-token-secret-with-more-than-32-bytes", "test");
    service = new AgentReadToolService(stateRepository, capabilityUses, tokenService, mapper);
    when(stateRepository.find("ses_1234567890abcdef"))
        .thenReturn(
            Optional.of(
                new BrowserStateRepository.Snapshot(
                    "tenant-test",
                    3,
                    new NodeEvent.StateUpdated(
                        "ses_1234567890abcdef",
                        9,
                        2,
                        "https://example.com/form?token=secret#fragment",
                        "Contact alice@example.com",
                        "state-hash",
                        "COMPLETE",
                        List.of(
                            new NodeEvent.InteractiveTarget(
                                "target-1",
                                "button",
                                "Call +86 138 1234 5678",
                                null,
                                true,
                                true))))));
    when(capabilityUses.claim(
            anyString(), anyString(), anyString(), anyString(), anyString(), any()))
        .thenReturn(1);
  }

  @Test
  void executesDataMinimizedSummaryAndConsumesCapability() {
    var now = Instant.parse("2026-07-26T00:00:00Z");
    var issued =
        tokenService.issue(
            "tenant-test",
            "ses_1234567890abcdef",
            "int_1234567890abcdef",
            "agt_1234567890abcdef",
            ToolId.GET_PAGE_SUMMARY,
            "example.com",
            "BROWSER_STATE_METADATA",
            RiskClass.R0_READ_ONLY,
            now.plusSeconds(60));
    var step =
        new PlanStep(
            "step-1",
            ToolId.GET_PAGE_SUMMARY,
            RiskClass.R0_READ_ONLY,
            null,
            "summary",
            List.of("user_goal"),
            TrustLevel.TRUSTED,
            List.of(),
            false,
            ExecutionStrategy.SEMANTIC_DOM,
            "COMPLETE",
            "SUMMARY_SCHEMA_VALID",
            issued.tokenId(),
            issued.token());

    var result =
        service.execute(
            "tenant-test", session(), "agt_1234567890abcdef", "int_1234567890abcdef", step, now);

    assertThat(result.status()).isEqualTo("VERIFIED");
    assertThat(result.output().get("url")).isEqualTo("https://example.com/form");
    assertThat(result.output().get("title")).isEqualTo("Contact a***@e***.com");
    assertThat(result.output().toString()).doesNotContain("secret", "138");
  }

  @Test
  void rejectsCapabilityReplay() {
    when(capabilityUses.claim(
            anyString(), anyString(), anyString(), anyString(), anyString(), any()))
        .thenReturn(0);
    var now = Instant.parse("2026-07-26T00:00:00Z");
    var issued =
        tokenService.issue(
            "tenant-test",
            "ses_1234567890abcdef",
            "int_1234567890abcdef",
            "agt_1234567890abcdef",
            ToolId.GET_URL,
            "example.com",
            "BROWSER_STATE_METADATA",
            RiskClass.R0_READ_ONLY,
            now.plusSeconds(60));
    var step =
        new PlanStep(
            "step-1",
            ToolId.GET_URL,
            RiskClass.R0_READ_ONLY,
            null,
            "url",
            List.of("user_goal"),
            TrustLevel.TRUSTED,
            List.of(),
            false,
            ExecutionStrategy.SEMANTIC_DOM,
            "COMPLETE",
            "URL_HOST_EQUALS_ALLOWED_DOMAIN",
            issued.tokenId(),
            issued.token());

    assertThatThrownBy(
            () ->
                service.execute(
                    "tenant-test",
                    session(),
                    "agt_1234567890abcdef",
                    "int_1234567890abcdef",
                    step,
                    now))
        .isInstanceOf(AgentReadToolService.ToolExecutionException.class)
        .hasMessage("CAPABILITY_TOKEN_REPLAYED");
  }

  private static SessionContext session() {
    var now = Instant.parse("2026-07-26T00:00:00Z");
    return new SessionContext(
        "ses_1234567890abcdef",
        "tenant-test",
        "profile-test",
        "node-test",
        "runtime-test",
        null,
        "proxy-test",
        1,
        3,
        2,
        1,
        ResourceClass.L2,
        SessionState.RUNNING,
        "",
        now,
        now);
  }
}
