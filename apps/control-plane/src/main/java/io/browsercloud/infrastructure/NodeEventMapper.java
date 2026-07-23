package io.browsercloud.infrastructure;

import com.google.protobuf.InvalidProtocolBufferException;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.proto.node.v1.BrowserCrashEvent;
import io.browsercloud.proto.node.v1.EventEnvelope;
import io.browsercloud.proto.node.v1.RuntimeStartedEvent;
import io.browsercloud.proto.node.v1.RuntimeStoppedEvent;
import org.springframework.stereotype.Component;

/** 将正式 Protobuf EventEnvelope 映射为 Coordinator 命令。 */
@Component
public class NodeEventMapper {

  static final String RUNTIME_STARTED = "RuntimeStarted";
  static final String RUNTIME_STOPPED = "RuntimeStopped";
  static final String BROWSER_CRASHED = "BrowserCrashed";
  private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

  public NodeEventReceived toCommand(EventEnvelope envelope) {
    requireText(envelope.getEventId(), "event_id");
    requireText(envelope.getTenantId(), "tenant_id");
    requireText(envelope.getSessionId(), "session_id");
    requireText(envelope.getEventType(), "event_type");
    if (envelope.getSequence() <= 0) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    if (envelope.getPayload().size() > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("event payload exceeds 64 KiB");
    }

    var event = parseEvent(envelope);
    if (!envelope.getSessionId().equals(eventSessionId(event))) {
      throw new IllegalArgumentException("payload session_id does not match envelope");
    }
    return new NodeEventReceived(
        envelope.getEventId(),
        envelope.getTenantId(),
        envelope.getSessionId(),
        envelope.getCoordinatorTerm(),
        envelope.getContextEpoch(),
        envelope.getOperationEpoch(),
        envelope.getSequence(),
        event);
  }

  private NodeEvent parseEvent(EventEnvelope envelope) {
    try {
      return switch (envelope.getEventType()) {
        case RUNTIME_STARTED -> {
          var payload = RuntimeStartedEvent.parseFrom(envelope.getPayload());
          yield new NodeEvent.RuntimeStarted(
              payload.getSessionId(),
              payload.getNodeId(),
              payload.getRuntimeBuildId(),
              payload.getPid(),
              payload.getBrowserGeneration(),
              payload.getCdpEndpoint());
        }
        case RUNTIME_STOPPED -> {
          var payload = RuntimeStoppedEvent.parseFrom(envelope.getPayload());
          yield new NodeEvent.RuntimeStopped(
              payload.getSessionId(), payload.getReason(), payload.getExitCode());
        }
        case BROWSER_CRASHED -> {
          var payload = BrowserCrashEvent.parseFrom(envelope.getPayload());
          yield new NodeEvent.RuntimeCrashed(
              payload.getSessionId(), payload.getCrashType(), payload.getReason());
        }
        default ->
            throw new IllegalArgumentException(
                "unsupported event_type: " + envelope.getEventType());
      };
    } catch (InvalidProtocolBufferException exception) {
      throw new IllegalArgumentException("event payload is not valid protobuf", exception);
    }
  }

  private String eventSessionId(NodeEvent event) {
    return switch (event) {
      case NodeEvent.RuntimeStarted started -> started.sessionId();
      case NodeEvent.RuntimeStopped stopped -> stopped.sessionId();
      case NodeEvent.RuntimeCrashed crashed -> crashed.sessionId();
      case NodeEvent.StateUpdated updated -> updated.sessionId();
    };
  }

  private void requireText(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException(field + " must contain 1 to 128 characters");
    }
  }
}
