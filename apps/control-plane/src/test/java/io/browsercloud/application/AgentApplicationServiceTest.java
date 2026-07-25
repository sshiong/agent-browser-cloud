package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.CreateAgentTaskRequest;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.agent.AgentModels.TaskState;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentApplicationServiceTest {

  @Mock private AgentTaskJpaRepository repository;
  @Mock private SessionRepository sessionRepository;
  @Mock private BrowserStateRepository stateRepository;
  @Mock private IdempotencyService idempotencyService;

  private AgentApplicationService service;

  @BeforeEach
  void setUp() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    service =
        new AgentApplicationService(
            repository,
            sessionRepository,
            stateRepository,
            idempotencyService,
            new PromptSecurityService(),
            new AgentCapabilityTokenService(
                mapper, "test-agent-capability-token-secret-with-more-than-32-bytes", "test"),
            mapper);
    when(sessionRepository.require(anyString())).thenReturn(runningSession());
    when(idempotencyService.claimAgentTask(
            anyString(), anyString(), anyString(), any(), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(4));
    lenient()
        .when(stateRepository.find(anyString()))
        .thenReturn(
            Optional.of(
                new BrowserStateRepository.Snapshot(
                    "tenant-test",
                    3,
                    new NodeEvent.StateUpdated(
                        "ses_1234567890abcdef",
                        9,
                        2,
                        "https://example.com/current",
                        "Example",
                        "hash",
                        "COMPLETE",
                        List.of()))));
  }

  @Test
  void createsBoundedPlanWithCapabilityHandlesButDoesNotExposeBearerTokens() {
    var view =
        service.create(
            "ses_1234567890abcdef",
            "tenant-test",
            request("https://example.com/start", List.of("example.com")),
            "idem-1");

    assertThat(view.state()).isEqualTo(TaskState.PLANNED);
    assertThat(view.plan().steps()).hasSize(4);
    assertThat(view.plan().maxActions()).isEqualTo(8);
    assertThat(view.plan().replanBudget()).isEqualTo(1);
    assertThat(view.plan().steps())
        .allSatisfy(
            step -> {
              assertThat(step.capabilityTokenId()).startsWith("cap_");
              assertThat(step.supportingSources()).contains("user_goal", "platform_policy");
            });
    assertThat(view.toString()).doesNotContain("browsercloud-local", "eyJ");
  }

  @Test
  void persistsBlockedTaskWhenNavigationLeavesAllowlist() {
    var view =
        service.create(
            "ses_1234567890abcdef",
            "tenant-test",
            request("https://evil.example/path", List.of("example.com")),
            "idem-2");

    assertThat(view.state()).isEqualTo(TaskState.BLOCKED);
    assertThat(view.blockedReason()).isEqualTo("DOMAIN_NOT_ALLOWED");
    assertThat(view.plan().steps()).isEmpty();
    assertThat(view.securityEvents())
        .extracting(event -> event.ruleCode())
        .contains("DOMAIN_NOT_ALLOWED");
  }

  private static CreateAgentTaskRequest request(String url, List<String> domains) {
    return new CreateAgentTaskRequest("打开授权页面并总结内容", url, domains, null, null, List.of());
  }

  private static SessionContext runningSession() {
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
