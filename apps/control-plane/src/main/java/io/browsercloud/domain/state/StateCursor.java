package io.browsercloud.domain.state;

import java.time.Instant;

/**
 * 状态指针。
 *
 * <p>Browser State Engine 的状态指针，用于追踪当前状态版本和质量。
 */
public record StateCursor(
    String sessionId,
    long currentStateVersion,
    String currentStateHash,
    StateQuality stateQuality,
    long browserGeneration,
    long coordinatorTerm,
    long contextEpoch,
    long targetRevision,
    long networkRevision,
    String lastCheckpointId,
    long lastCheckpointVersion,
    long pendingEventCount,
    Instant updatedAt) {
  /**
   * 检查是否可以执行语义操作。
   *
   * <p>只有状态质量为 COMPLETE 或 DEPTH_LIMITED 时才能执行语义操作。
   *
   * @return 是否可以执行语义操作
   */
  public boolean canExecuteSemanticAction() {
    return stateQuality == StateQuality.COMPLETE || stateQuality == StateQuality.DEPTH_LIMITED;
  }

  /**
   * 检查状态是否有效。
   *
   * @return 状态是否有效
   */
  public boolean isValid() {
    return stateQuality != StateQuality.INVALID && stateQuality != StateQuality.RESYNCING;
  }

  /**
   * 创建新的状态版本。
   *
   * @param newHash 新的状态哈希
   * @return 新的 StateCursor
   */
  public StateCursor nextStateVersion(String newHash) {
    return new StateCursor(
        sessionId,
        currentStateVersion + 1,
        newHash,
        stateQuality,
        browserGeneration,
        coordinatorTerm,
        contextEpoch,
        targetRevision,
        networkRevision,
        lastCheckpointId,
        lastCheckpointVersion,
        pendingEventCount,
        Instant.now());
  }

  /**
   * 创建新的 target_revision。
   *
   * @return 新的 StateCursor
   */
  public StateCursor nextTargetRevision() {
    return new StateCursor(
        sessionId,
        currentStateVersion,
        currentStateHash,
        stateQuality,
        browserGeneration,
        coordinatorTerm,
        contextEpoch,
        targetRevision + 1,
        networkRevision,
        lastCheckpointId,
        lastCheckpointVersion,
        pendingEventCount,
        Instant.now());
  }

  /**
   * 更新状态质量。
   *
   * @param newQuality 新的状态质量
   * @return 新的 StateCursor
   */
  public StateCursor withQuality(StateQuality newQuality) {
    return new StateCursor(
        sessionId,
        currentStateVersion,
        currentStateHash,
        newQuality,
        browserGeneration,
        coordinatorTerm,
        contextEpoch,
        targetRevision,
        networkRevision,
        lastCheckpointId,
        lastCheckpointVersion,
        pendingEventCount,
        Instant.now());
  }
}
