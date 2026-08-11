package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.proto.node.v1.AgentActionFailedEvent;
import io.browsercloud.proto.node.v1.AgentNavigationFailedEvent;
import io.browsercloud.proto.node.v1.BrowserStateDiffEvent;
import io.browsercloud.proto.node.v1.BrowserStateEvent;
import io.browsercloud.proto.node.v1.DiffTruncatedEvent;
import io.browsercloud.proto.node.v1.EventEnvelope;
import io.browsercloud.proto.node.v1.ExtensionBackgroundPolicy;
import io.browsercloud.proto.node.v1.HumanTakeoverEndedEvent;
import io.browsercloud.proto.node.v1.HumanTakeoverReadyEvent;
import io.browsercloud.proto.node.v1.InteractiveTargetState;
import io.browsercloud.proto.node.v1.RuntimeResourcesAdjustedEvent;
import io.browsercloud.proto.node.v1.RuntimeStartedEvent;
import io.browsercloud.proto.node.v1.RuntimeStoppedEvent;
import io.browsercloud.proto.node.v1.SessionEvidenceCapturedEvent;
import io.browsercloud.proto.node.v1.TargetBounds;
import org.junit.jupiter.api.Test;

class NodeEventMapperTest {

  private final NodeEventMapper mapper = new NodeEventMapper();

  @Test
  void shouldMapVersionedRuntimeStartedEvent() {
    var payload =
        RuntimeStartedEvent.newBuilder()
            .setSessionId("ses_test")
            .setNodeId("node-test")
            .setRuntimeBuildId("runtime-test")
            .setPid(42)
            .setBrowserGeneration(1)
            .setCdpEndpoint("http://127.0.0.1:9222")
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_test")
            .setEventType(NodeEventMapper.RUNTIME_STARTED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setCoordinatorTerm(2)
            .setContextEpoch(3)
            .setOperationEpoch(4)
            .setSequence(1)
            .setPayload(payload.toByteString())
            .build();

    var command = mapper.toCommand(envelope);

    assertThat(command.eventId()).isEqualTo("evt_test");
    assertThat(command.coordinatorTerm()).isEqualTo(2);
    assertThat(command.contextEpoch()).isEqualTo(3);
    assertThat(command.operationEpoch()).isEqualTo(4);
    assertThat(command.event()).isInstanceOf(NodeEvent.RuntimeStarted.class);
  }

  @Test
  void shouldMapCommittedProfileCheckpointFromRuntimeStopped() {
    var payload =
        RuntimeStoppedEvent.newBuilder()
            .setSessionId("ses_test")
            .setReason("user_request")
            .setProfileId("profile-test")
            .setCheckpointId("chk_1_test")
            .setCheckpointEpoch(1)
            .setProfileWriteEpoch(2)
            .setCoreSizeBytes(42)
            .setCheckpointFileCount(3)
            .setRestoreStatus("TECHNICAL_READY")
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_stopped")
            .setEventType(NodeEventMapper.RUNTIME_STOPPED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setSequence(1)
            .setPayload(payload.toByteString())
            .build();

    assertThat(mapper.toCommand(envelope).event())
        .isInstanceOfSatisfying(
            NodeEvent.RuntimeStopped.class,
            stopped -> {
              assertThat(stopped.profileId()).isEqualTo("profile-test");
              assertThat(stopped.checkpointEpoch()).isEqualTo(1);
              assertThat(stopped.profileWriteEpoch()).isEqualTo(2);
              assertThat(stopped.coreSizeBytes()).isEqualTo(42);
              assertThat(stopped.restoreStatus()).isEqualTo("TECHNICAL_READY");
            });
  }

  @Test
  void shouldMapOptionalNonCgroupResourceAdjustmentAcknowledgement() {
    var payload =
        RuntimeResourcesAdjustedEvent.newBuilder()
            .setSessionId("ses_test")
            .setNodeId("node-test")
            .setOldResourceClass("L2")
            .setOldCpuMillis(600)
            .setOldMemoryRequestMib(768)
            .setOldMemoryLimitMib(1280)
            .setOldPidLimit(256)
            .setOldTabBudget(8)
            .setNewResourceClass("L2")
            .setNewCpuMillis(900)
            .setNewMemoryRequestMib(1024)
            .setNewMemoryLimitMib(1792)
            .setNewPidLimit(256)
            .setNewTabBudget(8)
            .setReason("SUSTAINED_MEMORY_PRESSURE")
            .setOperationId("op-resource")
            .setOldStateCollectorBudgetPercent(100)
            .setNewStateCollectorBudgetPercent(75)
            .setOldRemoteDesktopBitrateKbps(8000)
            .setNewRemoteDesktopBitrateKbps(6000)
            .setOldExtensionCpuWeight(100)
            .setNewExtensionCpuWeight(150)
            .setOldMediaEncoderSlots(1)
            .setNewMediaEncoderSlots(2)
            .setOldExtensionBackgroundPolicy(
                ExtensionBackgroundPolicy.newBuilder().addPausedExtensionIds("extension.old"))
            .setNewExtensionBackgroundPolicy(
                ExtensionBackgroundPolicy.newBuilder().addPausedExtensionIds("extension.new"))
            .setOldSuccessTraceSamplePercent(100)
            .setNewSuccessTraceSamplePercent(10)
            .setOldSuccessScreenshotSamplePercent(100)
            .setNewSuccessScreenshotSamplePercent(10)
            .setOldObserverFrameRateFps(30)
            .setNewObserverFrameRateFps(5)
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt-resource")
            .setEventType(NodeEventMapper.RUNTIME_RESOURCES_ADJUSTED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setSequence(1)
            .setPayload(payload.toByteString())
            .build();

    assertThat(mapper.toCommand(envelope).event())
        .isInstanceOfSatisfying(
            NodeEvent.RuntimeResourcesAdjusted.class,
            adjusted -> {
              assertThat(adjusted.oldStateCollectorBudgetPercent()).isEqualTo(100);
              assertThat(adjusted.newStateCollectorBudgetPercent()).isEqualTo(75);
              assertThat(adjusted.oldRemoteDesktopBitrateKbps()).isEqualTo(8000);
              assertThat(adjusted.newRemoteDesktopBitrateKbps()).isEqualTo(6000);
              assertThat(adjusted.oldExtensionCpuWeight()).isEqualTo(100);
              assertThat(adjusted.newExtensionCpuWeight()).isEqualTo(150);
              assertThat(adjusted.oldMediaEncoderSlots()).isEqualTo(1);
              assertThat(adjusted.newMediaEncoderSlots()).isEqualTo(2);
              assertThat(adjusted.oldPausedExtensionIds()).containsExactly("extension.old");
              assertThat(adjusted.newPausedExtensionIds()).containsExactly("extension.new");
              assertThat(adjusted.oldSuccessTraceSamplePercent()).isEqualTo(100);
              assertThat(adjusted.newSuccessTraceSamplePercent()).isEqualTo(10);
              assertThat(adjusted.oldSuccessScreenshotSamplePercent()).isEqualTo(100);
              assertThat(adjusted.newSuccessScreenshotSamplePercent()).isEqualTo(10);
              assertThat(adjusted.oldObserverFrameRateFps()).isEqualTo(30);
              assertThat(adjusted.newObserverFrameRateFps()).isEqualTo(5);
            });
  }

  @Test
  void shouldAcceptLegacyResourceAdjustmentWithoutNonCgroupFields() {
    var payload =
        RuntimeResourcesAdjustedEvent.newBuilder()
            .setSessionId("ses_test")
            .setNodeId("node-test")
            .setOldResourceClass("L2")
            .setOldCpuMillis(600)
            .setOldMemoryRequestMib(768)
            .setOldMemoryLimitMib(1280)
            .setOldPidLimit(256)
            .setOldTabBudget(8)
            .setNewResourceClass("L2")
            .setNewCpuMillis(900)
            .setNewMemoryRequestMib(1024)
            .setNewMemoryLimitMib(1792)
            .setNewPidLimit(256)
            .setNewTabBudget(8)
            .setReason("SUSTAINED_MEMORY_PRESSURE")
            .setOperationId("op-resource")
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt-resource-legacy")
            .setEventType(NodeEventMapper.RUNTIME_RESOURCES_ADJUSTED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setSequence(1)
            .setPayload(payload.toByteString())
            .build();

    assertThat(mapper.toCommand(envelope).event())
        .isInstanceOfSatisfying(
            NodeEvent.RuntimeResourcesAdjusted.class,
            adjusted -> {
              assertThat(adjusted.oldStateCollectorBudgetPercent()).isNull();
              assertThat(adjusted.newRemoteDesktopBitrateKbps()).isNull();
              assertThat(adjusted.oldExtensionCpuWeight()).isNull();
              assertThat(adjusted.newPausedExtensionIds()).isNull();
              assertThat(adjusted.oldSuccessTraceSamplePercent()).isNull();
              assertThat(adjusted.newSuccessTraceSamplePercent()).isNull();
              assertThat(adjusted.oldObserverFrameRateFps()).isNull();
              assertThat(adjusted.newObserverFrameRateFps()).isNull();
            });
  }

  @Test
  void shouldRejectOneSidedSuccessTraceSamplingAcknowledgement() {
    var payload =
        RuntimeResourcesAdjustedEvent.newBuilder()
            .setSessionId("ses_test")
            .setNodeId("node-test")
            .setOldResourceClass("L2")
            .setOldCpuMillis(600)
            .setOldMemoryRequestMib(768)
            .setOldMemoryLimitMib(1280)
            .setOldPidLimit(256)
            .setOldTabBudget(8)
            .setNewResourceClass("L2")
            .setNewCpuMillis(600)
            .setNewMemoryRequestMib(768)
            .setNewMemoryLimitMib(1280)
            .setNewPidLimit(256)
            .setNewTabBudget(8)
            .setReason("MAXIMUM_NON_CORE_MITIGATION")
            .setOperationId("op-resource")
            .setNewSuccessTraceSamplePercent(10)
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt-resource-invalid-trace")
            .setEventType(NodeEventMapper.RUNTIME_RESOURCES_ADJUSTED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setSequence(1)
            .setPayload(payload.toByteString())
            .build();

    assertThatThrownBy(() -> mapper.toCommand(envelope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-cgroup");
  }

  @Test
  void shouldRejectOneSidedObserverFrameRateAcknowledgement() {
    var payload =
        RuntimeResourcesAdjustedEvent.newBuilder()
            .setSessionId("ses_test")
            .setNodeId("node-test")
            .setOldResourceClass("L2")
            .setOldCpuMillis(600)
            .setOldMemoryRequestMib(768)
            .setOldMemoryLimitMib(1280)
            .setOldPidLimit(256)
            .setOldTabBudget(8)
            .setNewResourceClass("L2")
            .setNewCpuMillis(600)
            .setNewMemoryRequestMib(768)
            .setNewMemoryLimitMib(1280)
            .setNewPidLimit(256)
            .setNewTabBudget(8)
            .setReason("MAXIMUM_NON_CORE_MITIGATION")
            .setOperationId("op-resource")
            .setNewObserverFrameRateFps(5)
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt-resource-invalid-observer-fps")
            .setEventType(NodeEventMapper.RUNTIME_RESOURCES_ADJUSTED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setSequence(1)
            .setPayload(payload.toByteString())
            .build();

    assertThatThrownBy(() -> mapper.toCommand(envelope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-cgroup");
  }

  @Test
  void shouldRejectPayloadForAnotherSession() {
    var payload = RuntimeStartedEvent.newBuilder().setSessionId("ses_other").build().toByteString();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_test")
            .setEventType(NodeEventMapper.RUNTIME_STARTED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setSequence(1)
            .setPayload(payload)
            .build();

    assertThatThrownBy(() -> mapper.toCommand(envelope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  void shouldRejectOversizedPayload() {
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_test")
            .setEventType(NodeEventMapper.RUNTIME_STARTED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setSequence(1)
            .setPayload(ByteString.copyFrom(new byte[65 * 1024]))
            .build();

    assertThatThrownBy(() -> mapper.toCommand(envelope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("64 KiB");
  }

  @Test
  void shouldAcceptTheBoundedBackpressureTruncationReason() {
    var payload =
        DiffTruncatedEvent.newBuilder()
            .setSessionId("ses_test")
            .setReason("BACKPRESSURE_LIMIT")
            .setLastGoodStateVersion(7)
            .setCurrentStateVersion(9)
            .setAffectedRoot("document")
            .setEstimatedTargets(40)
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_backpressure")
            .setEventType(NodeEventMapper.DIFF_TRUNCATED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setContextEpoch(3)
            .setSequence(2)
            .setPayload(payload.toByteString())
            .build();

    assertThat(mapper.toCommand(envelope).event())
        .isInstanceOfSatisfying(
            NodeEvent.DiffTruncated.class,
            event -> assertThat(event.reason()).isEqualTo("BACKPRESSURE_LIMIT"));
  }

  @Test
  void shouldMapNativeRegionResyncDiffMetadata() {
    var payload =
        BrowserStateDiffEvent.newBuilder()
            .setSessionId("ses_test")
            .setBaseStateVersion(7)
            .setStateVersion(8)
            .setTargetRevision(3)
            .setUrl("https://example.test/app")
            .setTitle("App")
            .setContentHash("hash-8")
            .setStateQuality("COMPLETE")
            .setDocumentReadyState("complete")
            .setSnapshotKind("REGION_RESYNC")
            .setRequestedRootRef("#app")
            .addRemovedTargetRefs("target:3:old")
            .addUpsertedTargets(
                InteractiveTargetState.newBuilder()
                    .setTargetRef("target:3:new")
                    .setRole("button")
                    .setEnabled(true)
                    .setVisible(true))
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_region")
            .setEventType(NodeEventMapper.BROWSER_STATE_DIFF)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setContextEpoch(3)
            .setSequence(2)
            .setPayload(payload.toByteString())
            .build();

    assertThat(mapper.toCommand(envelope).event())
        .isInstanceOfSatisfying(
            NodeEvent.StateDiff.class,
            diff -> {
              assertThat(diff.snapshotKind()).isEqualTo("REGION_RESYNC");
              assertThat(diff.requestedRootRef()).isEqualTo("#app");
              assertThat(diff.baseStateVersion()).isEqualTo(7);
              assertThat(diff.removedTargetRefs()).containsExactly("target:3:old");
            });
  }

  @Test
  void shouldRejectRegionResyncDiffWithoutBoundedRoot() {
    var payload =
        BrowserStateDiffEvent.newBuilder()
            .setSessionId("ses_test")
            .setBaseStateVersion(7)
            .setStateVersion(8)
            .setTargetRevision(3)
            .setUrl("https://example.test/app")
            .setContentHash("hash-8")
            .setStateQuality("COMPLETE")
            .setSnapshotKind("REGION_RESYNC")
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_region_invalid")
            .setEventType(NodeEventMapper.BROWSER_STATE_DIFF)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setSequence(2)
            .setPayload(payload.toByteString())
            .build();

    assertThatThrownBy(() -> mapper.toCommand(envelope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requested_root_ref");
  }

  @Test
  void shouldMapBrowserStateAndInteractiveTarget() {
    var payload =
        BrowserStateEvent.newBuilder()
            .setSessionId("ses_test")
            .setStateVersion(7)
            .setTargetRevision(6)
            .setUrl("https://example.test")
            .setTitle("Example")
            .setContentHash("hash-7")
            .setStateQuality("COMPLETE")
            .setDocumentReadyState("complete")
            .setNetworkQuietMillis(1_500)
            .setNetworkEvidenceFresh(true)
            .addTargets(
                InteractiveTargetState.newBuilder()
                    .setTargetRef("target:7:0")
                    .setRole("button")
                    .setName("Run")
                    .setBounds(
                        TargetBounds.newBuilder().setX(10).setY(20).setWidth(80).setHeight(32))
                    .setEnabled(true)
                    .setVisible(true)
                    .setSensitive(true))
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_state")
            .setEventType(NodeEventMapper.BROWSER_STATE_UPDATED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setContextEpoch(3)
            .setSequence(2)
            .setPayload(payload.toByteString())
            .build();

    var command = mapper.toCommand(envelope);

    assertThat(command.event())
        .isInstanceOfSatisfying(
            NodeEvent.StateUpdated.class,
            state -> {
              assertThat(state.stateVersion()).isEqualTo(7);
              assertThat(state.stateQuality()).isEqualTo("COMPLETE");
              assertThat(state.documentReadyState()).isEqualTo("complete");
              assertThat(state.networkQuietMillis()).isEqualTo(1_500);
              assertThat(state.networkEvidenceFresh()).isTrue();
              assertThat(state.targets()).hasSize(1);
              assertThat(state.targets().getFirst().role()).isEqualTo("button");
              assertThat(state.targets().getFirst().bounds().width()).isEqualTo(80);
              assertThat(state.targets().getFirst().sensitive()).isTrue();
            });
  }

  @Test
  void shouldMapAgentNavigationFailureWithoutLeakingNodeErrorDetails() {
    var payload =
        AgentNavigationFailedEvent.newBuilder()
            .setSessionId("ses_test")
            .setTaskId("agt_1234567890abcdef")
            .setStepId("step_1234567890abcd")
            .setErrorCode("NAVIGATION_FAILED")
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_agent_navigation_failed")
            .setEventType(NodeEventMapper.AGENT_NAVIGATION_FAILED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setContextEpoch(3)
            .setOperationEpoch(5)
            .setSequence(3)
            .setPayload(payload.toByteString())
            .build();

    assertThat(mapper.toCommand(envelope).event())
        .isInstanceOfSatisfying(
            NodeEvent.AgentNavigationFailed.class,
            failed -> {
              assertThat(failed.taskId()).isEqualTo("agt_1234567890abcdef");
              assertThat(failed.stepId()).isEqualTo("step_1234567890abcd");
              assertThat(failed.errorCode()).isEqualTo("NAVIGATION_FAILED");
            });
  }

  @Test
  void shouldMapAgentActionFailureAsStableCode() {
    var payload =
        AgentActionFailedEvent.newBuilder()
            .setSessionId("ses_test")
            .setTaskId("agt_1234567890abcdef")
            .setStepId("step_1234567890abcd")
            .setToolId("TYPE_TEXT")
            .setErrorCode("ACTION_PRECONDITION_FAILED")
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_agent_action_failed")
            .setEventType(NodeEventMapper.AGENT_ACTION_FAILED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setContextEpoch(3)
            .setOperationEpoch(5)
            .setSequence(3)
            .setPayload(payload.toByteString())
            .build();

    assertThat(mapper.toCommand(envelope).event())
        .isInstanceOfSatisfying(
            NodeEvent.AgentActionFailed.class,
            failed -> {
              assertThat(failed.toolId()).isEqualTo("TYPE_TEXT");
              assertThat(failed.errorCode()).isEqualTo("ACTION_PRECONDITION_FAILED");
            });
  }

  @Test
  void shouldMapCommittedSessionEvidenceWithoutWeakeningValidation() {
    var payload =
        SessionEvidenceCapturedEvent.newBuilder()
            .setSessionId("ses_test")
            .setEvidenceId("evd_1234567890abcdef")
            .setEvidenceKind("AGENT_ACTION_FAILURE")
            .setTaskId("agt_1234567890abcdef")
            .setStepId("step_1234567890abcd")
            .setCommandId("cmd_1234567890abcdef")
            .setContentSha256("a".repeat(64))
            .setContentBytes(1024)
            .setObjectKey(
                "tenants/tenant-test/profiles/profile-test/sessions/ses_test/evidence/"
                    + "evd_1234567890abcdef/screenshot.jpeg")
            .setCapturedAtMs(1_785_283_200_000L)
            .setMandatory(true)
            .setResult("COMMITTED")
            .setRedactionState("MASKED")
            .setRedactedRegionCount(2)
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_evidence")
            .setEventType(NodeEventMapper.SESSION_EVIDENCE_CAPTURED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setContextEpoch(3)
            .setSequence(4)
            .setPayload(payload.toByteString())
            .build();

    assertThat(mapper.toCommand(envelope).event())
        .isInstanceOfSatisfying(
            NodeEvent.EvidenceCaptured.class,
            evidence -> {
              assertThat(evidence.evidenceId()).isEqualTo("evd_1234567890abcdef");
              assertThat(evidence.mandatory()).isTrue();
              assertThat(evidence.result()).isEqualTo("COMMITTED");
              assertThat(evidence.contentBytes()).isEqualTo(1024);
              assertThat(evidence.redactionState()).isEqualTo("MASKED");
              assertThat(evidence.redactedRegionCount()).isEqualTo(2);
            });
  }

  @Test
  void shouldPreserveNMinusOneEvidenceAsLegacyUnverified() {
    var payload =
        SessionEvidenceCapturedEvent.newBuilder()
            .setSessionId("ses_test")
            .setEvidenceId("evd_1234567890abcdef")
            .setEvidenceKind("AGENT_ACTION_FAILURE")
            .setTaskId("agt_1234567890abcdef")
            .setStepId("step_1234567890abcd")
            .setCommandId("cmd_1234567890abcdef")
            .setContentSha256("a".repeat(64))
            .setContentBytes(1024)
            .setObjectKey(
                "tenants/tenant-test/profiles/profile-test/sessions/ses_test/evidence/"
                    + "evd_1234567890abcdef/screenshot.jpeg")
            .setCapturedAtMs(1_785_283_200_000L)
            .setMandatory(true)
            .setResult("COMMITTED")
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_evidence_n_minus_one")
            .setEventType(NodeEventMapper.SESSION_EVIDENCE_CAPTURED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setContextEpoch(3)
            .setSequence(4)
            .setPayload(payload.toByteString())
            .build();

    assertThat(mapper.toCommand(envelope).event())
        .isInstanceOfSatisfying(
            NodeEvent.EvidenceCaptured.class,
            evidence -> {
              assertThat(evidence.redactionState()).isEqualTo("LEGACY_UNVERIFIED");
              assertThat(evidence.redactedRegionCount()).isZero();
            });
  }

  @Test
  void shouldRejectEvidenceThatClaimsCommittedWithoutAnObject() {
    var payload =
        SessionEvidenceCapturedEvent.newBuilder()
            .setSessionId("ses_test")
            .setEvidenceId("evd_1234567890abcdef")
            .setEvidenceKind("AGENT_ACTION_SUCCESS")
            .setTaskId("agt_1234567890abcdef")
            .setStepId("step_1234567890abcd")
            .setCommandId("cmd_1234567890abcdef")
            .setContentSha256("a".repeat(64))
            .setContentBytes(1024)
            .setCapturedAtMs(1_785_283_200_000L)
            .setResult("COMMITTED")
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_evidence_invalid")
            .setEventType(NodeEventMapper.SESSION_EVIDENCE_CAPTURED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setSequence(4)
            .setPayload(payload.toByteString())
            .build();

    assertThatThrownBy(() -> mapper.toCommand(envelope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("committed evidence metadata");
  }

  @Test
  void shouldMapHumanTakeoverReadyWithResyncedState() {
    var state =
        BrowserStateEvent.newBuilder()
            .setSessionId("ses_test")
            .setStateVersion(8)
            .setTargetRevision(7)
            .setUrl("https://example.test/takeover")
            .setTitle("Takeover ready")
            .setContentHash("hash-8")
            .setStateQuality("COMPLETE")
            .build();
    var payload =
        HumanTakeoverReadyEvent.newBuilder()
            .setSessionId("ses_test")
            .setUserId("user-test")
            .setState(state)
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_takeover_ready")
            .setEventType(NodeEventMapper.HUMAN_TAKEOVER_READY)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setContextEpoch(3)
            .setOperationEpoch(5)
            .setSequence(3)
            .setPayload(payload.toByteString())
            .build();

    var command = mapper.toCommand(envelope);

    assertThat(command.event())
        .isInstanceOfSatisfying(
            NodeEvent.HumanTakeoverReady.class,
            ready -> {
              assertThat(ready.userId()).isEqualTo("user-test");
              assertThat(ready.state().stateVersion()).isEqualTo(8);
              assertThat(ready.state().stateQuality()).isEqualTo("COMPLETE");
            });
  }

  @Test
  void shouldRejectHumanTakeoverWithoutStateResync() {
    var payload =
        HumanTakeoverReadyEvent.newBuilder()
            .setSessionId("ses_test")
            .setUserId("user-test")
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_takeover_ready")
            .setEventType(NodeEventMapper.HUMAN_TAKEOVER_READY)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setSequence(3)
            .setPayload(payload.toByteString())
            .build();

    assertThatThrownBy(() -> mapper.toCommand(envelope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("state is required");
  }

  @Test
  void shouldMapGatewayDisconnectReasonWithResyncedState() {
    var state =
        BrowserStateEvent.newBuilder()
            .setSessionId("ses_test")
            .setStateVersion(9)
            .setTargetRevision(3)
            .setUrl("https://example.test")
            .setContentHash("hash-9")
            .setStateQuality("COMPLETE")
            .build();
    var payload =
        HumanTakeoverEndedEvent.newBuilder()
            .setSessionId("ses_test")
            .setUserId("user-test")
            .setReason("GATEWAY_DISCONNECT")
            .setState(state)
            .build();
    var envelope =
        EventEnvelope.newBuilder()
            .setEventId("evt_takeover_disconnected")
            .setEventType(NodeEventMapper.HUMAN_TAKEOVER_ENDED)
            .setTenantId("tenant-test")
            .setSessionId("ses_test")
            .setContextEpoch(3)
            .setOperationEpoch(5)
            .setSequence(4)
            .setPayload(payload.toByteString())
            .build();

    assertThat(mapper.toCommand(envelope).event())
        .isInstanceOfSatisfying(
            NodeEvent.HumanTakeoverEnded.class,
            ended -> {
              assertThat(ended.reason()).isEqualTo("GATEWAY_DISCONNECT");
              assertThat(ended.state().stateVersion()).isEqualTo(9);
            });
  }
}
