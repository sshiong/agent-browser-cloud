package io.browsercloud.coordinator;

/**
 * 启动 Session 命令。
 *
 * @param sessionId Session ID
 * @param requestedRuntimeBuildId 请求的 Runtime Build ID
 * @param idempotencyKey 幂等键
 */
public record StartSession(String sessionId, String requestedRuntimeBuildId, String idempotencyKey)
    implements SessionCommand {}
