package io.browsercloud.domain.operation;

import java.time.Instant;
import java.util.Set;

/**
 * 排他操作。
 *
 * <p>表示当前唯一具有浏览器写入权的操作。 同一 Session 同时只能有一个 Active 状态的 ExclusiveOperation。
 */
public record ExclusiveOperation(
    String operationId,
    String sessionId,
    OwnerType ownerType,
    String actorId,
    OperationMode mode,
    int priority,
    long coordinatorTerm,
    long contextEpoch,
    long operationEpoch,
    String workflowId,
    boolean cancellable,
    boolean preemptible,
    OperationPhase phase,
    OperationState state,
    Set<String> allowedCapabilities,
    Instant deadline,
    Instant createdAt,
    Instant completedAt) {
  /** 检查操作是否处于活跃状态。 */
  public boolean isActive() {
    return state == OperationState.ACTIVE;
  }

  /**
   * 检查操作是否已过期。
   *
   * @param now 当前时间
   * @return 是否已过期
   */
  public boolean isExpired(Instant now) {
    return !deadline.isAfter(now);
  }

  /**
   * 创建新的操作状态。
   *
   * @param newState 新状态
   * @return 新的 ExclusiveOperation
   */
  public ExclusiveOperation withState(OperationState newState) {
    Instant completedAt =
        (newState == OperationState.COMMITTED
                || newState == OperationState.ABORTED
                || newState == OperationState.TIMED_OUT)
            ? Instant.now()
            : this.completedAt;

    return new ExclusiveOperation(
        operationId,
        sessionId,
        ownerType,
        actorId,
        mode,
        priority,
        coordinatorTerm,
        contextEpoch,
        operationEpoch,
        workflowId,
        cancellable,
        preemptible,
        phase,
        newState,
        allowedCapabilities,
        deadline,
        createdAt,
        completedAt);
  }

  /**
   * 创建新的操作阶段。
   *
   * @param newPhase 新阶段
   * @return 新的 ExclusiveOperation
   */
  public ExclusiveOperation withPhase(OperationPhase newPhase) {
    return new ExclusiveOperation(
        operationId,
        sessionId,
        ownerType,
        actorId,
        mode,
        priority,
        coordinatorTerm,
        contextEpoch,
        operationEpoch,
        workflowId,
        cancellable,
        preemptible,
        newPhase,
        state,
        allowedCapabilities,
        deadline,
        createdAt,
        completedAt);
  }
}
