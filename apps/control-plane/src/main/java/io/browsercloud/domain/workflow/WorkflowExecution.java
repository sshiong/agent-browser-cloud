package io.browsercloud.domain.workflow;

import java.time.Instant;

/**
 * 工作流执行记录。
 *
 * <p>每个 Workflow 必须持久化，用于故障恢复和审计。
 */
public record WorkflowExecution(
    String workflowId,
    String sessionId,
    String operationId,
    String workflowType,
    int attempt,
    int priority,
    WorkflowState state,
    String phase,
    String workerId,
    long coordinatorTerm,
    long contextEpoch,
    long operationEpoch,
    Instant dispatchedAt,
    Instant startedAt,
    Instant heartbeatAt,
    Instant phaseDeadline,
    Instant operationDeadline,
    long cancellationEpoch,
    String idempotencyKey,
    String externalReceipt,
    String failureReason,
    Instant createdAt,
    Instant completedAt) {
  /**
   * 检查工作流是否处于终态。
   *
   * @return 是否处于终态
   */
  public boolean isTerminal() {
    return state == WorkflowState.COMPLETED
        || state == WorkflowState.CANCELLED
        || state == WorkflowState.COMPENSATED
        || state == WorkflowState.DEAD_LETTER;
  }

  /**
   * 检查工作流是否可以重试。
   *
   * @return 是否可以重试
   */
  public boolean canRetry() {
    return state == WorkflowState.FAILED;
  }

  /**
   * 检查心跳是否超时。
   *
   * @param now 当前时间
   * @param heartbeatTimeout 心跳超时时间
   * @return 心跳是否超时
   */
  public boolean isHeartbeatExpired(Instant now, java.time.Duration heartbeatTimeout) {
    if (heartbeatAt == null) {
      return false;
    }
    return heartbeatAt.plus(heartbeatTimeout).isBefore(now);
  }
}
