package io.browsercloud.coordinator;

import io.browsercloud.domain.capacity.RuntimeResourceLimits;

/**
 * 启动 Session 命令。
 *
 * @param sessionId Session ID
 * @param requestedRuntimeBuildId 请求的 Runtime Build ID
 * @param idempotencyKey 幂等键
 * @param resourceLimits Placement 已提交的 Runtime 强制边界
 */
public record StartSession(
    String sessionId,
    String requestedRuntimeBuildId,
    String idempotencyKey,
    RuntimeResourceLimits resourceLimits,
    String profileCheckpointId)
    implements SessionCommand {

  /** 兼容领域单测；生产 Application Service 必须传入已提交 Placement 的 limits。 */
  public StartSession(String sessionId, String requestedRuntimeBuildId, String idempotencyKey) {
    this(sessionId, requestedRuntimeBuildId, idempotencyKey, null, null);
  }

  public StartSession(
      String sessionId,
      String requestedRuntimeBuildId,
      String idempotencyKey,
      RuntimeResourceLimits resourceLimits) {
    this(sessionId, requestedRuntimeBuildId, idempotencyKey, resourceLimits, null);
  }
}
