package io.browsercloud.infrastructure;

import com.google.protobuf.InvalidProtocolBufferException;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.proto.node.v1.AgentActionFailedEvent;
import io.browsercloud.proto.node.v1.AgentNavigationFailedEvent;
import io.browsercloud.proto.node.v1.BrowserCrashEvent;
import io.browsercloud.proto.node.v1.BrowserStateDiffEvent;
import io.browsercloud.proto.node.v1.BrowserStateEvent;
import io.browsercloud.proto.node.v1.DiffTruncatedEvent;
import io.browsercloud.proto.node.v1.EventEnvelope;
import io.browsercloud.proto.node.v1.HumanTakeoverEndedEvent;
import io.browsercloud.proto.node.v1.HumanTakeoverReadyEvent;
import io.browsercloud.proto.node.v1.RuntimeResourcesAdjustedEvent;
import io.browsercloud.proto.node.v1.RuntimeStartedEvent;
import io.browsercloud.proto.node.v1.RuntimeStoppedEvent;
import org.springframework.stereotype.Component;

/** 将正式 Protobuf EventEnvelope 映射为 Coordinator 命令。 */
@Component
public class NodeEventMapper {

  static final String RUNTIME_STARTED = "RuntimeStarted";
  static final String RUNTIME_STOPPED = "RuntimeStopped";
  static final String RUNTIME_RESOURCES_ADJUSTED = "RuntimeResourcesAdjusted";
  static final String BROWSER_CRASHED = "BrowserCrashed";
  static final String BROWSER_STATE_UPDATED = "BrowserStateUpdated";
  static final String BROWSER_STATE_DIFF = "BrowserStateDiff";
  static final String DIFF_TRUNCATED = "DiffTruncated";
  static final String AGENT_NAVIGATION_FAILED = "AgentNavigationFailed";
  static final String AGENT_ACTION_FAILED = "AgentActionFailed";
  static final String HUMAN_TAKEOVER_READY = "HumanTakeoverReady";
  static final String HUMAN_TAKEOVER_ENDED = "HumanTakeoverEnded";
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
          if (!payload.getProxyBindingId().isBlank()) {
            requireText(payload.getExitIp(), "exit_ip");
            requireText(payload.getExitCountry(), "exit_country");
            requireText(payload.getExitAsn(), "exit_asn");
          } else if (!payload.getExitIp().isBlank()
              || !payload.getExitCountry().isBlank()
              || !payload.getExitAsn().isBlank()) {
            throw new IllegalArgumentException("direct Runtime cannot report proxy exit metadata");
          }
          yield new NodeEvent.RuntimeStarted(
              payload.getSessionId(),
              payload.getNodeId(),
              payload.getRuntimeBuildId(),
              payload.getPid(),
              payload.getBrowserGeneration(),
              payload.getCdpEndpoint(),
              payload.getProxyBindingId(),
              payload.getExitIp(),
              payload.getExitCountry(),
              payload.getExitAsn());
        }
        case RUNTIME_STOPPED -> {
          var payload = RuntimeStoppedEvent.parseFrom(envelope.getPayload());
          boolean hasCheckpoint = !payload.getCheckpointId().isBlank();
          if (hasCheckpoint) {
            requireText(payload.getProfileId(), "profile_id");
            if (payload.getCheckpointEpoch() <= 0 || payload.getProfileWriteEpoch() <= 0) {
              throw new IllegalArgumentException("profile checkpoint epochs must be positive");
            }
          } else if (!payload.getProfileId().isBlank()
              || payload.getCheckpointEpoch() != 0
              || payload.getProfileWriteEpoch() != 0
              || payload.getCoreSizeBytes() != 0
              || payload.getCheckpointFileCount() != 0) {
            throw new IllegalArgumentException("empty profile checkpoint has metadata");
          }
          if (!payload.getRestoreStatus().equals("EMPTY")
              && !payload.getRestoreStatus().equals("TECHNICAL_READY")) {
            throw new IllegalArgumentException("unsupported profile restore_status");
          }
          yield new NodeEvent.RuntimeStopped(
              payload.getSessionId(),
              payload.getReason(),
              payload.getExitCode(),
              payload.getProfileId(),
              payload.getCheckpointId(),
              payload.getCheckpointEpoch(),
              payload.getProfileWriteEpoch(),
              payload.getCoreSizeBytes(),
              payload.getCheckpointFileCount(),
              payload.getRestoreStatus());
        }
        case RUNTIME_RESOURCES_ADJUSTED -> {
          var payload = RuntimeResourcesAdjustedEvent.parseFrom(envelope.getPayload());
          requireText(payload.getNodeId(), "node_id");
          requireText(payload.getOldResourceClass(), "old_resource_class");
          requireText(payload.getNewResourceClass(), "new_resource_class");
          requireText(payload.getReason(), "reason");
          requireText(payload.getOperationId(), "operation_id");
          if (payload.getOldCpuMillis() <= 0
              || payload.getNewCpuMillis() <= 0
              || payload.getOldMemoryRequestMib() <= 0
              || payload.getNewMemoryRequestMib() <= 0
              || payload.getOldMemoryLimitMib() < payload.getOldMemoryRequestMib()
              || payload.getNewMemoryLimitMib() < payload.getNewMemoryRequestMib()
              || payload.getOldPidLimit() < 32
              || payload.getNewPidLimit() < 32
              || payload.getOldTabBudget() <= 0
              || payload.getNewTabBudget() <= 0) {
            throw new IllegalArgumentException("resource adjustment limits are invalid");
          }
          var oldStateCollectorBudget =
              payload.hasOldStateCollectorBudgetPercent()
                  ? payload.getOldStateCollectorBudgetPercent()
                  : null;
          var oldRemoteDesktopBitrate =
              payload.hasOldRemoteDesktopBitrateKbps()
                  ? payload.getOldRemoteDesktopBitrateKbps()
                  : null;
          var newStateCollectorBudget =
              payload.hasNewStateCollectorBudgetPercent()
                  ? payload.getNewStateCollectorBudgetPercent()
                  : null;
          var newRemoteDesktopBitrate =
              payload.hasNewRemoteDesktopBitrateKbps()
                  ? payload.getNewRemoteDesktopBitrateKbps()
                  : null;
          var oldExtensionCpuWeight =
              payload.hasOldExtensionCpuWeight() ? payload.getOldExtensionCpuWeight() : null;
          var newExtensionCpuWeight =
              payload.hasNewExtensionCpuWeight() ? payload.getNewExtensionCpuWeight() : null;
          var oldMediaEncoderSlots =
              payload.hasOldMediaEncoderSlots() ? payload.getOldMediaEncoderSlots() : null;
          var newMediaEncoderSlots =
              payload.hasNewMediaEncoderSlots() ? payload.getNewMediaEncoderSlots() : null;
          var oldFreezeBackgroundTabs =
              payload.hasOldFreezeBackgroundTabs() ? payload.getOldFreezeBackgroundTabs() : null;
          var newFreezeBackgroundTabs =
              payload.hasNewFreezeBackgroundTabs() ? payload.getNewFreezeBackgroundTabs() : null;
          var oldBlockNewTabs = payload.hasOldBlockNewTabs() ? payload.getOldBlockNewTabs() : null;
          var newBlockNewTabs = payload.hasNewBlockNewTabs() ? payload.getNewBlockNewTabs() : null;
          if ((oldStateCollectorBudget == null) != (newStateCollectorBudget == null)
              || (oldRemoteDesktopBitrate == null) != (newRemoteDesktopBitrate == null)
              || (oldExtensionCpuWeight == null) != (newExtensionCpuWeight == null)
              || (oldMediaEncoderSlots == null) != (newMediaEncoderSlots == null)
              || (oldFreezeBackgroundTabs == null) != (newFreezeBackgroundTabs == null)
              || (oldBlockNewTabs == null) != (newBlockNewTabs == null)
              || (oldStateCollectorBudget != null
                  && (oldStateCollectorBudget < 10
                      || oldStateCollectorBudget > 100
                      || newStateCollectorBudget < 10
                      || newStateCollectorBudget > 100))
              || (oldRemoteDesktopBitrate != null
                  && (oldRemoteDesktopBitrate < 0
                      || oldRemoteDesktopBitrate > 100_000
                      || newRemoteDesktopBitrate < 0
                      || newRemoteDesktopBitrate > 100_000))
              || (oldExtensionCpuWeight != null
                  && (oldExtensionCpuWeight < 1
                      || oldExtensionCpuWeight > 10_000
                      || newExtensionCpuWeight < 1
                      || newExtensionCpuWeight > 10_000))
              || (oldMediaEncoderSlots != null
                  && (oldMediaEncoderSlots < 0
                      || oldMediaEncoderSlots > 32
                      || newMediaEncoderSlots < 0
                      || newMediaEncoderSlots > 32))) {
            throw new IllegalArgumentException("non-cgroup resource adjustment limits are invalid");
          }
          yield new NodeEvent.RuntimeResourcesAdjusted(
              payload.getSessionId(),
              payload.getNodeId(),
              payload.getOldResourceClass(),
              payload.getOldCpuMillis(),
              payload.getOldMemoryRequestMib(),
              payload.getOldMemoryLimitMib(),
              payload.getOldPidLimit(),
              payload.getOldTabBudget(),
              payload.getNewResourceClass(),
              payload.getNewCpuMillis(),
              payload.getNewMemoryRequestMib(),
              payload.getNewMemoryLimitMib(),
              payload.getNewPidLimit(),
              payload.getNewTabBudget(),
              oldStateCollectorBudget,
              oldRemoteDesktopBitrate,
              newStateCollectorBudget,
              newRemoteDesktopBitrate,
              oldExtensionCpuWeight,
              newExtensionCpuWeight,
              oldMediaEncoderSlots,
              newMediaEncoderSlots,
              oldFreezeBackgroundTabs,
              newFreezeBackgroundTabs,
              oldBlockNewTabs,
              newBlockNewTabs,
              payload.getReason(),
              payload.getOperationId());
        }
        case BROWSER_CRASHED -> {
          var payload = BrowserCrashEvent.parseFrom(envelope.getPayload());
          yield new NodeEvent.RuntimeCrashed(
              payload.getSessionId(), payload.getCrashType(), payload.getReason());
        }
        case BROWSER_STATE_UPDATED -> {
          var payload = BrowserStateEvent.parseFrom(envelope.getPayload());
          yield state(payload);
        }
        case BROWSER_STATE_DIFF -> {
          var payload = BrowserStateDiffEvent.parseFrom(envelope.getPayload());
          validateStateMetadata(
              payload.getSessionId(),
              payload.getStateVersion(),
              payload.getTargetRevision(),
              payload.getUrl(),
              payload.getTitle(),
              payload.getContentHash(),
              payload.getStateQuality());
          if (payload.getBaseStateVersion() <= 0
              || payload.getStateVersion() <= payload.getBaseStateVersion()) {
            throw new IllegalArgumentException("State Diff versions are invalid");
          }
          if (payload.getUpsertedTargetsCount() + payload.getRemovedTargetRefsCount() > 500) {
            throw new IllegalArgumentException("State Diff target count exceeds 500");
          }
          payload.getRemovedTargetRefsList().forEach(value -> requireText(value, "target_ref"));
          var upsertedTargets =
              payload.getUpsertedTargetsList().stream().map(this::target).toList();
          yield new NodeEvent.StateDiff(
              payload.getSessionId(),
              payload.getBaseStateVersion(),
              payload.getStateVersion(),
              payload.getTargetRevision(),
              payload.getUrl(),
              payload.getTitle(),
              payload.getContentHash(),
              payload.getStateQuality(),
              upsertedTargets,
              payload.getRemovedTargetRefsList());
        }
        case DIFF_TRUNCATED -> {
          var payload = DiffTruncatedEvent.parseFrom(envelope.getPayload());
          requireText(payload.getReason(), "reason");
          requireText(payload.getAffectedRoot(), "affected_root");
          if (payload.getLastGoodStateVersion() <= 0
              || payload.getCurrentStateVersion() <= payload.getLastGoodStateVersion()) {
            throw new IllegalArgumentException("DiffTruncated versions are invalid");
          }
          yield new NodeEvent.DiffTruncated(
              payload.getSessionId(),
              payload.getReason(),
              payload.getLastGoodStateVersion(),
              payload.getCurrentStateVersion(),
              payload.getAffectedRoot(),
              payload.getEstimatedTargets());
        }
        case AGENT_NAVIGATION_FAILED -> {
          var payload = AgentNavigationFailedEvent.parseFrom(envelope.getPayload());
          requireText(payload.getTaskId(), "task_id");
          requireText(payload.getStepId(), "step_id");
          requireText(payload.getErrorCode(), "error_code");
          yield new NodeEvent.AgentNavigationFailed(
              payload.getSessionId(),
              payload.getTaskId(),
              payload.getStepId(),
              payload.getErrorCode());
        }
        case AGENT_ACTION_FAILED -> {
          var payload = AgentActionFailedEvent.parseFrom(envelope.getPayload());
          requireText(payload.getTaskId(), "task_id");
          requireText(payload.getStepId(), "step_id");
          requireText(payload.getToolId(), "tool_id");
          requireText(payload.getErrorCode(), "error_code");
          if (!java.util.Set.of("CLICK_TARGET", "TYPE_TEXT", "SCROLL", "WAIT_FOR")
              .contains(payload.getToolId())) {
            throw new IllegalArgumentException("unsupported Agent Action tool_id");
          }
          yield new NodeEvent.AgentActionFailed(
              payload.getSessionId(),
              payload.getTaskId(),
              payload.getStepId(),
              payload.getToolId(),
              payload.getErrorCode());
        }
        case HUMAN_TAKEOVER_READY -> {
          var payload = HumanTakeoverReadyEvent.parseFrom(envelope.getPayload());
          if (!payload.hasState()) {
            throw new IllegalArgumentException("HumanTakeoverReady state is required");
          }
          var state = state(payload.getState());
          if (!payload.getSessionId().equals(state.sessionId())) {
            throw new IllegalArgumentException("takeover state session_id does not match payload");
          }
          yield new NodeEvent.HumanTakeoverReady(
              payload.getSessionId(), payload.getUserId(), state);
        }
        case HUMAN_TAKEOVER_ENDED -> {
          var payload = HumanTakeoverEndedEvent.parseFrom(envelope.getPayload());
          if (!payload.hasState()) {
            throw new IllegalArgumentException("HumanTakeoverEnded state is required");
          }
          var state = state(payload.getState());
          if (!payload.getSessionId().equals(state.sessionId())) {
            throw new IllegalArgumentException("takeover state session_id does not match payload");
          }
          var reason = payload.getReason().isBlank() ? "USER_RELEASE" : payload.getReason();
          if (!reason.equals("USER_RELEASE") && !reason.equals("GATEWAY_DISCONNECT")) {
            throw new IllegalArgumentException("unsupported HumanTakeoverEnded reason");
          }
          yield new NodeEvent.HumanTakeoverEnded(
              payload.getSessionId(), payload.getUserId(), reason, state);
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
      case NodeEvent.RuntimeResourcesAdjusted adjusted -> adjusted.sessionId();
      case NodeEvent.RuntimeCrashed crashed -> crashed.sessionId();
      case NodeEvent.StateUpdated updated -> updated.sessionId();
      case NodeEvent.StateDiff diff -> diff.sessionId();
      case NodeEvent.DiffTruncated truncated -> truncated.sessionId();
      case NodeEvent.AgentNavigationFailed failed -> failed.sessionId();
      case NodeEvent.AgentActionFailed failed -> failed.sessionId();
      case NodeEvent.HumanTakeoverReady ready -> ready.sessionId();
      case NodeEvent.HumanTakeoverEnded ended -> ended.sessionId();
    };
  }

  private NodeEvent.StateUpdated state(BrowserStateEvent payload) {
    validateStateMetadata(
        payload.getSessionId(),
        payload.getStateVersion(),
        payload.getTargetRevision(),
        payload.getUrl(),
        payload.getTitle(),
        payload.getContentHash(),
        payload.getStateQuality());
    if (payload.getTargetsCount() > 500) {
      throw new IllegalArgumentException("Browser State target count exceeds 500");
    }
    var targets = payload.getTargetsList().stream().map(this::target).toList();
    return new NodeEvent.StateUpdated(
        payload.getSessionId(),
        payload.getStateVersion(),
        payload.getTargetRevision(),
        payload.getUrl(),
        payload.getTitle(),
        payload.getContentHash(),
        payload.getStateQuality(),
        targets,
        payload.getSnapshotKind(),
        payload.getRequestedRootRef());
  }

  private void validateStateMetadata(
      String sessionId,
      long stateVersion,
      long targetRevision,
      String url,
      String title,
      String stateHash,
      String stateQuality) {
    requireText(sessionId, "session_id");
    if (stateVersion <= 0 || targetRevision <= 0) {
      throw new IllegalArgumentException("State versions must be positive");
    }
    if (url.isBlank() || url.length() > 8192 || title.length() > 1024) {
      throw new IllegalArgumentException("State document metadata is invalid");
    }
    requireText(stateHash, "content_hash");
    if (!java.util.Set.of("COMPLETE", "DEPTH_LIMITED", "RESYNCING", "DEGRADED", "INVALID")
        .contains(stateQuality)) {
      throw new IllegalArgumentException("unsupported state_quality");
    }
  }

  private NodeEvent.InteractiveTarget target(
      io.browsercloud.proto.node.v1.InteractiveTargetState target) {
    requireText(target.getTargetRef(), "target_ref");
    requireText(target.getRole(), "target_role");
    return new NodeEvent.InteractiveTarget(
        target.getTargetRef(),
        target.getRole(),
        target.hasName() ? target.getName() : null,
        target.hasBounds()
            ? new NodeEvent.Bounds(
                target.getBounds().getX(),
                target.getBounds().getY(),
                target.getBounds().getWidth(),
                target.getBounds().getHeight())
            : null,
        target.getEnabled(),
        target.getVisible(),
        target.getSensitive());
  }

  private void requireText(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException(field + " must contain 1 to 128 characters");
    }
  }
}
