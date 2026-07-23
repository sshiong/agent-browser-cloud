package io.browsercloud.coordinator;

/**
 * Node 命令。
 *
 * @param messageId 消息 ID
 * @param commandType 命令类型
 * @param sessionId Session ID
 * @param tenantId 租户 ID
 * @param coordinatorTerm Coordinator 世代
 * @param contextEpoch Context 版本
 * @param operationEpoch Operation 版本
 * @param idempotencyKey 幂等键
 * @param payload 载荷
 */
public record NodeCommand(
    String messageId,
    String commandType,
    String sessionId,
    String tenantId,
    long coordinatorTerm,
    long contextEpoch,
    long operationEpoch,
    String idempotencyKey,
    byte[] payload) {}
