package io.browsercloud.coordinator;

/**
 * Node 事件接收命令。
 *
 * @param eventId 事件 ID
 * @param tenantId 租户 ID
 * @param sessionId Session ID
 * @param coordinatorTerm Coordinator 世代
 * @param contextEpoch Context 版本
 * @param operationEpoch Operation 版本
 * @param sequence Node 事件序列
 * @param event Node 事件
 */
public record NodeEventReceived(
    String eventId,
    String tenantId,
    String sessionId,
    long coordinatorTerm,
    long contextEpoch,
    long operationEpoch,
    long sequence,
    NodeEvent event)
    implements SessionCommand {}
