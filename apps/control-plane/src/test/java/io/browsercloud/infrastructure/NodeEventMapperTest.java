package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.proto.node.v1.BrowserStateEvent;
import io.browsercloud.proto.node.v1.EventEnvelope;
import io.browsercloud.proto.node.v1.HumanTakeoverEndedEvent;
import io.browsercloud.proto.node.v1.HumanTakeoverReadyEvent;
import io.browsercloud.proto.node.v1.InteractiveTargetState;
import io.browsercloud.proto.node.v1.RuntimeStartedEvent;
import io.browsercloud.proto.node.v1.RuntimeStoppedEvent;
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
            .addTargets(
                InteractiveTargetState.newBuilder()
                    .setTargetRef("target:7:0")
                    .setRole("button")
                    .setName("Run")
                    .setBounds(
                        TargetBounds.newBuilder().setX(10).setY(20).setWidth(80).setHeight(32))
                    .setEnabled(true)
                    .setVisible(true))
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
              assertThat(state.targets()).hasSize(1);
              assertThat(state.targets().getFirst().role()).isEqualTo("button");
              assertThat(state.targets().getFirst().bounds().width()).isEqualTo(80);
            });
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
