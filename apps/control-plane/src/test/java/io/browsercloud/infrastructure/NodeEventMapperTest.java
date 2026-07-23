package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.proto.node.v1.BrowserStateEvent;
import io.browsercloud.proto.node.v1.EventEnvelope;
import io.browsercloud.proto.node.v1.InteractiveTargetState;
import io.browsercloud.proto.node.v1.RuntimeStartedEvent;
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
}
