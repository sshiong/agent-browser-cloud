package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.persistence.ToolCapabilityUseJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentActionToolServiceTest {
  private final BrowserStateRepository states = mock(BrowserStateRepository.class);
  private final ToolCapabilityUseJpaRepository uses = mock(ToolCapabilityUseJpaRepository.class);
  private final AgentCapabilityTokenService tokens = mock(AgentCapabilityTokenService.class);
  private final NodeCommandGateway gateway = mock(NodeCommandGateway.class);
  private final AgentControlPolicyService policies = mock(AgentControlPolicyService.class);
  private final AgentActionAttemptService attempts = mock(AgentActionAttemptService.class);
  private final BrowserCapacityApplicationService capacity =
      mock(BrowserCapacityApplicationService.class);
  private final SessionContext session = mock(SessionContext.class);
  private final AgentActionToolService service =
      new AgentActionToolService(states, uses, tokens, gateway, policies, attempts, capacity);

  @Test
  void rejectsNonInteractiveSingleTargetsBeforeCapabilityUseOrDispatch() {
    prepare(false);
    for (var identity : List.of("target-observable", "e0123456789abcdef012345678")) {
      assertThatThrownBy(() -> authorize(step(ToolId.CLICK_TARGET, input(identity))))
          .isInstanceOf(AgentActionToolService.ActionToolException.class)
          .hasMessage("TARGET_NOT_ACTIONABLE");
    }
    verifyNoInteractions(tokens, uses, attempts, gateway);
  }

  @Test
  void rejectsNonInteractiveBatchTargetBeforeCapabilityUseOrDispatch() {
    prepare(false);
    var action =
        new ActionInput(
            "action_1",
            ToolId.CLICK_TARGET,
            "target-observable",
            "e0123456789abcdef012345678",
            7L,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            1);
    var input =
        new StepInput(
            null, null, null, null, null, null, null, null, null, false, 1, List.of(action), true);
    assertThatThrownBy(() -> authorize(step(ToolId.EXECUTE_ACTIONS, input)))
        .isInstanceOf(AgentActionToolService.ActionToolException.class)
        .hasMessage("TARGET_NOT_ACTIONABLE");
    verifyNoInteractions(tokens, uses, attempts, gateway);
  }

  @Test
  void interactiveTargetStillRequiresCapabilityVerification() {
    prepare(true);
    when(tokens.verify(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("CAPABILITY_VERIFICATION_REACHED"));
    assertThatThrownBy(() -> authorize(step(ToolId.CLICK_TARGET, input("target-observable"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CAPABILITY_VERIFICATION_REACHED");
    verifyNoInteractions(uses, attempts, gateway);
  }

  @Test
  void rejectsNonInteractiveDragDestinationBeforeCapabilityUseOrDispatch() {
    prepare(true);
    var original = states.find("ses_test").orElseThrow().state();
    var destination =
        new NodeEvent.InteractiveTarget(
            "target-destination",
            "button",
            "Ambiguous",
            new NodeEvent.Bounds(150, 10, 100, 30),
            true,
            true,
            false,
            "eabcdef0123456789abcdef012",
            null,
            null,
            false,
            null,
            null,
            false,
            "main",
            true,
            false,
            null);
    var state =
        new NodeEvent.StateUpdated(
            "ses_test",
            7,
            7,
            "https://example.test/",
            "Test",
            "state-hash",
            "COMPLETE",
            List.of(original.targets().getFirst(), destination));
    when(states.find("ses_test"))
        .thenReturn(
            Optional.of(
                new BrowserStateRepository.Snapshot(
                    "tenant-test", 1, state, Instant.parse("2026-10-03T00:00:00Z"))));
    var input =
        new StepInput(
            "target-observable",
            7L,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            1,
            List.of(),
            true,
            null,
            null,
            null,
            "target-destination",
            null,
            null,
            null,
            null,
            null,
            100);
    assertThatThrownBy(() -> authorize(step(ToolId.DRAG_TARGET, input)))
        .isInstanceOf(AgentActionToolService.ActionToolException.class)
        .hasMessage("DRAG_DESTINATION_NOT_ACTIONABLE");
    verifyNoInteractions(tokens, uses, attempts, gateway);
  }

  private void prepare(boolean interactive) {
    when(session.sessionId()).thenReturn("ses_test");
    when(session.nodeId()).thenReturn("node_test");
    when(session.contextEpoch()).thenReturn(1L);
    when(capacity.nodeHasCapability("node_test", "agentActionCancellation", "authority-watch-v1"))
        .thenReturn(true);
    when(policies.require("ses_test", "tenant-test"))
        .thenReturn(new AgentControlPolicyService.Policy(AgentControlMode.SAFE, 3));
    var target =
        new NodeEvent.InteractiveTarget(
            "target-observable",
            "button",
            "Same action",
            new NodeEvent.Bounds(10, 10, 100, 30),
            true,
            true,
            false,
            "e0123456789abcdef012345678",
            null,
            null,
            false,
            null,
            null,
            interactive,
            "main",
            true,
            false,
            null);
    var state =
        new NodeEvent.StateUpdated(
            "ses_test",
            7,
            7,
            "https://example.test/",
            "Test",
            "state-hash",
            "COMPLETE",
            List.of(target));
    when(states.find("ses_test"))
        .thenReturn(
            Optional.of(
                new BrowserStateRepository.Snapshot(
                    "tenant-test", 1, state, Instant.parse("2026-10-03T00:00:00Z"))));
  }

  private void authorize(PlanStep step) {
    service.authorizeAndQueue(
        "tenant-test",
        session,
        mock(ExclusiveOperation.class),
        "agt_test",
        "intent_test",
        step,
        Instant.parse("2026-10-03T00:00:00Z"));
  }

  private static StepInput input(String identity) {
    return new StepInput(identity, 7L, null, null, null, null, null, null, null, false, 1);
  }

  private static PlanStep step(ToolId tool, StepInput input) {
    return new PlanStep(
        "step_test",
        tool,
        RiskClass.R1_LOW_RISK_CHANGE,
        null,
        input,
        "Test",
        List.of("user_goal"),
        TrustLevel.TRUSTED,
        List.of(),
        false,
        ExecutionStrategy.SEMANTIC_DOM,
        "COMPLETE",
        "ACTION",
        "cap_test",
        "signed_test");
  }
}
