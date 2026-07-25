package io.browsercloud.coordinator;

import java.util.List;

/**
 * Node 事件。
 *
 * <p>Browser Node 发送到 Control Plane 的事件。
 */
public sealed interface NodeEvent
    permits NodeEvent.RuntimeStarted,
        NodeEvent.RuntimeStopped,
        NodeEvent.RuntimeCrashed,
        NodeEvent.StateUpdated,
        NodeEvent.StateDiff,
        NodeEvent.DiffTruncated,
        NodeEvent.AgentNavigationFailed,
        NodeEvent.HumanTakeoverReady,
        NodeEvent.HumanTakeoverEnded {

  /** Runtime 启动事件。 */
  record RuntimeStarted(
      String sessionId,
      String nodeId,
      String runtimeBuildId,
      long pid,
      long browserGeneration,
      String cdpEndpoint,
      String proxyBindingId,
      String exitIp,
      String exitCountry,
      String exitAsn)
      implements NodeEvent {}

  /** Runtime 停止事件。 */
  record RuntimeStopped(
      String sessionId,
      String reason,
      int exitCode,
      String profileId,
      String checkpointId,
      long checkpointEpoch,
      long profileWriteEpoch,
      long coreSizeBytes,
      long checkpointFileCount,
      String restoreStatus)
      implements NodeEvent {}

  /** Runtime 崩溃事件。 */
  record RuntimeCrashed(String sessionId, String crashType, String reason) implements NodeEvent {}

  /** 状态更新事件。 */
  record StateUpdated(
      String sessionId,
      long stateVersion,
      long targetRevision,
      String url,
      String title,
      String stateHash,
      String stateQuality,
      List<InteractiveTarget> targets)
      implements NodeEvent {
    public StateUpdated {
      targets = List.copyOf(targets);
    }
  }

  record StateDiff(
      String sessionId,
      long baseStateVersion,
      long stateVersion,
      long targetRevision,
      String url,
      String title,
      String stateHash,
      String stateQuality,
      List<InteractiveTarget> upsertedTargets,
      List<String> removedTargetRefs)
      implements NodeEvent {
    public StateDiff {
      upsertedTargets = List.copyOf(upsertedTargets);
      removedTargetRefs = List.copyOf(removedTargetRefs);
    }
  }

  record DiffTruncated(
      String sessionId,
      String reason,
      long lastGoodStateVersion,
      long currentStateVersion,
      String affectedRoot,
      long estimatedTargets)
      implements NodeEvent {}

  record AgentNavigationFailed(String sessionId, String taskId, String stepId, String errorCode)
      implements NodeEvent {}

  record InteractiveTarget(
      String targetRef,
      String role,
      String name,
      Bounds bounds,
      boolean enabled,
      boolean visible) {}

  record Bounds(double x, double y, double width, double height) {}

  record HumanTakeoverReady(String sessionId, String userId, StateUpdated state)
      implements NodeEvent {}

  record HumanTakeoverEnded(String sessionId, String userId, String reason, StateUpdated state)
      implements NodeEvent {}
}
