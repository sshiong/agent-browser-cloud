package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeCommand;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.persistence.ToolCapabilityUseJpaRepository;
import io.browsercloud.proto.node.v1.AgentActionCommand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    for (var identity : List.of("target-observable", "e0123456789abcdef01234567")) {
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
            "e0123456789abcdef01234567",
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
            "e0123456789abcdef01234567",
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

  @Test
  void capturesMainDocumentIdentityOnlyForCapableNodes() throws Exception {
    for (var capable : List.of(false, true)) {
      clearInvocations(gateway, uses, attempts, tokens);
      prepare(true);
      enableDispatch(capable);
      authorize(step(ToolId.CLICK_TARGET, input("e0123456789abcdef01234567")));
      var command = ArgumentCaptor.forClass(NodeCommand.class);
      verify(gateway).send(command.capture());
      var payload = AgentActionCommand.parseFrom(command.getValue().payload());
      org.assertj.core.api.Assertions.assertThat(payload.getMainDocumentElementId())
          .isEqualTo(capable ? "e0123456789abcdef01234567" : "");
      org.assertj.core.api.Assertions.assertThat(payload.getMainDocumentEndElementId()).isEmpty();
      org.assertj.core.api.Assertions.assertThat(payload.getTargetRevision()).isEqualTo(7);
      verify(uses)
          .claim(
              "cap_fixture",
              "tenant-test",
              "ses_test",
              "agt_test",
              "CLICK_TARGET",
              Instant.parse("2026-10-03T00:00:00Z"));
    }
  }

  @Test
  void childFrameKeepsExactRevisionEvenWhenNodeSupportsMainDocumentRebinding() throws Exception {
    prepare(true);
    var original = states.find("ses_test").orElseThrow().state();
    var target =
        new NodeEvent.InteractiveTarget(
            "target-observable",
            "button",
            "Action",
            new NodeEvent.Bounds(10, 10, 100, 30),
            true,
            true,
            false,
            "e0123456789abcdef01234567",
            null,
            null,
            false,
            null,
            null,
            true,
            "child-frame",
            true,
            false,
            null);
    when(states.find("ses_test"))
        .thenReturn(
            Optional.of(
                new BrowserStateRepository.Snapshot(
                    "tenant-test",
                    1,
                    new NodeEvent.StateUpdated(
                        "ses_test",
                        7,
                        7,
                        original.url(),
                        "Test",
                        "state-hash",
                        "COMPLETE",
                        List.of(target)))));
    enableDispatch(true);
    authorize(step(ToolId.CLICK_TARGET, input("target-observable")));
    var command = ArgumentCaptor.forClass(NodeCommand.class);
    verify(gateway).send(command.capture());
    var payload = AgentActionCommand.parseFrom(command.getValue().payload());
    org.assertj.core.api.Assertions.assertThat(payload.getMainDocumentElementId()).isEmpty();
    org.assertj.core.api.Assertions.assertThat(payload.getTargetRef())
        .isEqualTo("target-observable");
    org.assertj.core.api.Assertions.assertThat(payload.getTargetRevision()).isEqualTo(7);
  }

  @Test
  void capturesBothDragIdentitiesOnlyWhenBothAreInTheMainDocument() throws Exception {
    prepare(true);
    var original = states.find("ses_test").orElseThrow().state();
    var destinationId = "e" + "b".repeat(24);
    var destination =
        new NodeEvent.InteractiveTarget(
            "target-destination",
            "button",
            "Drop",
            new NodeEvent.Bounds(150, 10, 100, 30),
            true,
            true,
            false,
            destinationId,
            null,
            null,
            false,
            null,
            null,
            true,
            "main",
            true,
            false,
            null);
    when(states.find("ses_test"))
        .thenReturn(
            Optional.of(
                new BrowserStateRepository.Snapshot(
                    "tenant-test",
                    1,
                    new NodeEvent.StateUpdated(
                        "ses_test",
                        7,
                        7,
                        original.url(),
                        "Test",
                        "state-hash",
                        "COMPLETE",
                        List.of(original.targets().getFirst(), destination)))));
    enableDispatch(true);
    var input =
        new StepInput(
            "e0123456789abcdef01234567",
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
            destinationId,
            null,
            null,
            null,
            null,
            null,
            100);
    advanceRevision();
    authorize(step(ToolId.DRAG_TARGET, input));
    var command = ArgumentCaptor.forClass(NodeCommand.class);
    verify(gateway).send(command.capture());
    var payload = AgentActionCommand.parseFrom(command.getValue().payload());
    org.assertj.core.api.Assertions.assertThat(payload.getMainDocumentElementId())
        .isEqualTo("e0123456789abcdef01234567");
    org.assertj.core.api.Assertions.assertThat(payload.getMainDocumentEndElementId())
        .isEqualTo(destinationId);
    org.assertj.core.api.Assertions.assertThat(payload.getTargetRevision()).isEqualTo(7);
    org.assertj.core.api.Assertions.assertThat(payload.getBaseStateVersion()).isEqualTo(8);
  }

  private void enableDispatch(boolean capable) {
    enableDispatch(capable, RiskClass.R1_LOW_RISK_CHANGE);
  }

  private void enableDispatch(boolean capable, RiskClass capabilityRisk) {
    when(capacity.nodeHasCapability(
            "node_test", "agentSingleTargetRebind", "main-document-element-v1"))
        .thenReturn(capable);
    var claims = mock(AgentCapabilityTokenService.CapabilityClaims.class);
    when(claims.tokenId()).thenReturn("cap_fixture");
    when(claims.riskClass()).thenReturn(capabilityRisk);
    when(tokens.verify(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(claims);
    when(uses.claim(any(), any(), any(), any(), any(), any())).thenReturn(1);
    when(attempts.reserve(any(), any(), any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(new AgentActionAttemptService.Reservation("attempt_fixture", true));
  }

  private void authorize(PlanStep step) {
    authorize(step, RiskClass.R1_LOW_RISK_CHANGE);
  }

  private void authorize(PlanStep step, RiskClass taskRisk) {
    service.authorizeAndQueue(
        "tenant-test",
        session,
        mock(ExclusiveOperation.class),
        "agt_test",
        "intent_test",
        step,
        Instant.parse("2026-10-03T00:00:00Z"),
        taskRisk);
  }

  private void advanceRevision() {
    var original = states.find("ses_test").orElseThrow().state();
    when(states.find("ses_test"))
        .thenReturn(
            Optional.of(
                new BrowserStateRepository.Snapshot(
                    "tenant-test",
                    1,
                    new NodeEvent.StateUpdated(
                        "ses_test",
                        8,
                        8,
                        original.url(),
                        "Test",
                        "new-state-hash",
                        "COMPLETE",
                        original.targets()))));
  }

  @Test
  void persistedCompleteIdentitySurvivesRevisionBeforeAuthorization() throws Exception {
    prepare(true);
    advanceRevision();
    enableDispatch(true);
    authorize(step(ToolId.CLICK_TARGET, input("e0123456789abcdef01234567")));
    var command = ArgumentCaptor.forClass(NodeCommand.class);
    verify(gateway).send(command.capture());
    var payload = AgentActionCommand.parseFrom(command.getValue().payload());
    org.assertj.core.api.Assertions.assertThat(payload.getTargetRevision()).isEqualTo(7);
    org.assertj.core.api.Assertions.assertThat(payload.getBaseStateVersion()).isEqualTo(8);
    org.assertj.core.api.Assertions.assertThat(payload.getBaseContentHash())
        .isEqualTo("new-state-hash");
    org.assertj.core.api.Assertions.assertThat(payload.getMainDocumentElementId())
        .isEqualTo("e0123456789abcdef01234567");
  }

  @Test
  void stalePlanIdentityStillRequiresTrustedRiskCapabilityAndNewNode() {
    for (var risk :
        List.of(
            RiskClass.R2_DATA_CHANGE,
            RiskClass.R3_ACCOUNT_CHANGE,
            RiskClass.R4_FINANCIAL,
            RiskClass.R5_SECURITY)) {
      prepare(true);
      advanceRevision();
      enableDispatch(true);
      assertThatThrownBy(
              () -> authorize(step(ToolId.CLICK_TARGET, input("e0123456789abcdef01234567")), risk))
          .hasMessage("TARGET_REVISION_MISMATCH");
    }
    prepare(true);
    advanceRevision();
    enableDispatch(false);
    assertThatThrownBy(
            () -> authorize(step(ToolId.CLICK_TARGET, input("e0123456789abcdef01234567"))))
        .hasMessage("TARGET_REVISION_MISMATCH");
    enableDispatch(true, RiskClass.R0_READ_ONLY);
    assertThatThrownBy(
            () -> authorize(step(ToolId.CLICK_TARGET, input("e0123456789abcdef01234567"))))
        .hasMessage("TARGET_REVISION_MISMATCH");
    verifyNoInteractions(uses, attempts, gateway);
  }

  @Test
  void staleSlotMissingIdentityAndNonInteractiveTargetsNeverAcquirePlanAdvance() {
    prepare(true);
    advanceRevision();
    enableDispatch(true);
    for (var reference : List.of("target-observable", "e" + "f".repeat(24))) {
      assertThatThrownBy(() -> authorize(step(ToolId.CLICK_TARGET, input(reference))))
          .hasMessage("TARGET_REVISION_MISMATCH");
    }
    prepare(false);
    advanceRevision();
    assertThatThrownBy(
            () -> authorize(step(ToolId.CLICK_TARGET, input("e0123456789abcdef01234567"))))
        .hasMessage("TARGET_REVISION_MISMATCH");
    verifyNoInteractions(tokens, uses, attempts, gateway);
  }

  @Test
  void staleIdentityInChildFrameOrAFutureRevisionCannotAdvance() {
    prepare(true);
    enableDispatch(true);
    var original = states.find("ses_test").orElseThrow().state();
    var child =
        new NodeEvent.InteractiveTarget(
            "target-observable",
            "button",
            "Action",
            new NodeEvent.Bounds(10, 10, 100, 30),
            true,
            true,
            false,
            "e0123456789abcdef01234567",
            null,
            null,
            false,
            null,
            null,
            true,
            "child-frame",
            true,
            false,
            null);
    when(states.find("ses_test"))
        .thenReturn(
            Optional.of(
                new BrowserStateRepository.Snapshot(
                    "tenant-test",
                    1,
                    new NodeEvent.StateUpdated(
                        "ses_test",
                        8,
                        8,
                        original.url(),
                        "Test",
                        "new-state-hash",
                        "COMPLETE",
                        List.of(child)))));
    assertThatThrownBy(
            () -> authorize(step(ToolId.CLICK_TARGET, input("e0123456789abcdef01234567"))))
        .hasMessage("TARGET_REVISION_MISMATCH");
    prepare(true);
    var future =
        new StepInput(
            "e0123456789abcdef01234567", 8L, null, null, null, null, null, null, null, false, 1);
    assertThatThrownBy(() -> authorize(step(ToolId.CLICK_TARGET, future)))
        .hasMessage("TARGET_REVISION_MISMATCH");
    verifyNoInteractions(tokens, uses, attempts, gateway);
  }

  @Test
  void staleAuthorizedOtpKeepsTheSameSealedPayloadAndBoundedAttempts() throws Exception {
    prepareSensitiveInput();
    advanceRevision();
    enableDispatch(true, RiskClass.R2_DATA_CHANGE);
    var original = sensitiveStep(ActionDataClass.OTP);
    var value = original.input();
    var captured =
        new StepInput(
            "e0123456789abcdef01234567",
            value.targetRevision(),
            value.sealedPayload(),
            value.payloadHash(),
            value.payloadLength(),
            value.dataClass(),
            null,
            null,
            null,
            true,
            3);
    authorize(step(ToolId.TYPE_TEXT, captured, RiskClass.R2_DATA_CHANGE), RiskClass.R2_DATA_CHANGE);
    var command = ArgumentCaptor.forClass(NodeCommand.class);
    verify(gateway).send(command.capture());
    var payload = AgentActionCommand.parseFrom(command.getValue().payload());
    org.assertj.core.api.Assertions.assertThat(payload.getMainDocumentElementId())
        .isEqualTo("e0123456789abcdef01234567");
    org.assertj.core.api.Assertions.assertThat(payload.getSealedText())
        .isEqualTo(value.sealedPayload());
    org.assertj.core.api.Assertions.assertThat(payload.getMaximumAttempts()).isEqualTo(3);
    org.assertj.core.api.Assertions.assertThat(payload.getTargetRevision()).isEqualTo(7);
  }

  @Test
  void unknownAndHighRiskTasksKeepExactRevisionDespiteLowRiskSteps() throws Exception {
    for (var risk : RiskClass.values()) {
      if (risk.ordinal() <= RiskClass.R1_LOW_RISK_CHANGE.ordinal()) continue;
      clearInvocations(gateway);
      prepare(true);
      enableDispatch(true);
      authorize(step(ToolId.CLICK_TARGET, input("target-observable")), risk);
      assertExactRevisionDispatch();
    }
    clearInvocations(gateway);
    service.authorizeAndQueue(
        "tenant-test",
        session,
        mock(ExclusiveOperation.class),
        "agt_test",
        "intent_test",
        step(ToolId.CLICK_TARGET, input("target-observable")),
        Instant.parse("2026-10-03T00:00:00Z"));
    assertExactRevisionDispatch();
  }

  @Test
  void capabilityRiskMismatchCannotGrantRebinding() throws Exception {
    prepare(true);
    enableDispatch(true, RiskClass.R0_READ_ONLY);
    authorize(step(ToolId.CLICK_TARGET, input("target-observable")));
    assertExactRevisionDispatch();
  }

  @Test
  void ordinaryDataChangeKeepsExactRevision() throws Exception {
    prepare(true);
    enableDispatch(true, RiskClass.R2_DATA_CHANGE);
    authorize(
        step(ToolId.CLICK_TARGET, input("target-observable"), RiskClass.R2_DATA_CHANGE),
        RiskClass.R2_DATA_CHANGE);
    assertExactRevisionDispatch();
  }

  @Test
  void authorizedAutonomousCredentialsAndOtpCanRebindWithoutRelaxingAccountDecisions()
      throws Exception {
    for (var dataClass : List.of(ActionDataClass.CREDENTIAL, ActionDataClass.OTP)) {
      for (var risk :
          List.of(
              RiskClass.R2_DATA_CHANGE,
              RiskClass.R3_ACCOUNT_CHANGE,
              RiskClass.R4_FINANCIAL,
              RiskClass.R5_SECURITY)) {
        clearInvocations(gateway, uses);
        prepareSensitiveInput();
        enableDispatch(true, RiskClass.R2_DATA_CHANGE);
        authorize(sensitiveStep(dataClass), risk);
        var command = ArgumentCaptor.forClass(NodeCommand.class);
        verify(gateway).send(command.capture());
        var payload = AgentActionCommand.parseFrom(command.getValue().payload());
        org.assertj.core.api.Assertions.assertThat(payload.getMainDocumentElementId())
            .isEqualTo(risk == RiskClass.R2_DATA_CHANGE ? "e0123456789abcdef01234567" : "");
        org.assertj.core.api.Assertions.assertThat(payload.getSealedText())
            .isEqualTo("sealed_fixture");
        org.assertj.core.api.Assertions.assertThat(payload.getAllowSensitiveTarget()).isTrue();
        org.assertj.core.api.Assertions.assertThat(payload.getMaximumAttempts()).isEqualTo(3);
        verify(uses).claim(any(), any(), any(), any(), eq("TYPE_TEXT"), any());
      }
    }
  }

  @Test
  void safeModeRejectsSensitiveInputBeforeRebindingOrCapabilityUse() {
    prepareSensitiveInput();
    when(policies.require("ses_test", "tenant-test"))
        .thenReturn(new AgentControlPolicyService.Policy(AgentControlMode.SAFE, 3));
    assertThatThrownBy(
            () -> authorize(sensitiveStep(ActionDataClass.OTP), RiskClass.R2_DATA_CHANGE))
        .isInstanceOf(AgentActionToolService.ActionToolException.class)
        .hasMessage("SENSITIVE_TARGET_FORBIDDEN");
    verifyNoInteractions(tokens, uses, attempts, gateway);
  }

  private void assertExactRevisionDispatch() throws Exception {
    var command = ArgumentCaptor.forClass(NodeCommand.class);
    verify(gateway).send(command.capture());
    var payload = AgentActionCommand.parseFrom(command.getValue().payload());
    org.assertj.core.api.Assertions.assertThat(payload.getMainDocumentElementId()).isEmpty();
    org.assertj.core.api.Assertions.assertThat(payload.getMainDocumentEndElementId()).isEmpty();
    org.assertj.core.api.Assertions.assertThat(payload.getTargetRevision()).isEqualTo(7);
  }

  private void prepareSensitiveInput() {
    prepare(true);
    when(policies.require("ses_test", "tenant-test"))
        .thenReturn(new AgentControlPolicyService.Policy(AgentControlMode.AUTONOMOUS, 3));
    var target =
        new NodeEvent.InteractiveTarget(
            "target-observable",
            "textbox",
            "Credential",
            new NodeEvent.Bounds(10, 10, 100, 30),
            true,
            true,
            true,
            "e0123456789abcdef01234567",
            null,
            null,
            false,
            null,
            null,
            true,
            "main",
            true,
            false,
            null);
    when(states.find("ses_test"))
        .thenReturn(
            Optional.of(
                new BrowserStateRepository.Snapshot(
                    "tenant-test",
                    1,
                    new NodeEvent.StateUpdated(
                        "ses_test",
                        7,
                        7,
                        "https://example.test/",
                        "Test",
                        "state-hash",
                        "COMPLETE",
                        List.of(target)))));
  }

  private static PlanStep sensitiveStep(ActionDataClass dataClass) {
    return step(
        ToolId.TYPE_TEXT,
        new StepInput(
            "target-observable",
            7L,
            "sealed_fixture",
            "fixture_hash",
            6,
            dataClass,
            null,
            null,
            null,
            true,
            3),
        RiskClass.R2_DATA_CHANGE);
  }

  private static StepInput input(String identity) {
    return new StepInput(identity, 7L, null, null, null, null, null, null, null, false, 1);
  }

  private static PlanStep step(ToolId tool, StepInput input) {
    return step(tool, input, RiskClass.R1_LOW_RISK_CHANGE);
  }

  private static PlanStep step(ToolId tool, StepInput input, RiskClass risk) {
    return new PlanStep(
        "step_test",
        tool,
        risk,
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
