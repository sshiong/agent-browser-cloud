package io.browsercloud.coordinator;

/**
 * Node 事件。
 *
 * <p>Browser Node 发送到 Control Plane 的事件。
 */
public sealed interface NodeEvent
    permits NodeEvent.RuntimeStarted,
        NodeEvent.RuntimeStopped,
        NodeEvent.RuntimeCrashed,
        NodeEvent.StateUpdated {

  /** Runtime 启动事件。 */
  record RuntimeStarted(
      String sessionId,
      String nodeId,
      String runtimeBuildId,
      long pid,
      long browserGeneration,
      String cdpEndpoint)
      implements NodeEvent {}

  /** Runtime 停止事件。 */
  record RuntimeStopped(String sessionId, String reason, int exitCode) implements NodeEvent {}

  /** Runtime 崩溃事件。 */
  record RuntimeCrashed(String sessionId, String crashType, String reason) implements NodeEvent {}

  /** 状态更新事件。 */
  record StateUpdated(String sessionId, long stateVersion, String stateHash, String stateQuality)
      implements NodeEvent {}
}
