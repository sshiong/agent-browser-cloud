package io.browsercloud.coordinator;

import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.operation.OperationState;
import java.util.Optional;

/**
 * Operation 仓储接口。
 *
 * <p>同一 Session 同时只能有一个 Active 状态的 ExclusiveOperation。
 */
public interface OperationRepository {

  /**
   * 确保无活跃 Operation。
   *
   * @param sessionId Session ID
   * @throws ActiveOperationExistsException 如果已有活跃 Operation
   */
  void ensureNoActiveOperation(String sessionId);

  /**
   * 获取当前活跃 Operation。
   *
   * @param sessionId Session ID
   * @return 活跃 Operation（如果存在）
   */
  Optional<ExclusiveOperation> findActive(String sessionId);

  long nextOperationEpoch(String sessionId);

  /**
   * 插入新的 Operation。
   *
   * @param operation Operation
   */
  void insert(ExclusiveOperation operation);

  /**
   * 转换 Operation 状态。
   *
   * <p>使用 CAS 保证状态转换安全。
   *
   * @param operationId Operation ID
   * @param expectedState 预期状态
   * @param targetState 目标状态
   * @throws StaleOperationException 如果当前状态不匹配
   */
  void transition(String operationId, OperationState expectedState, OperationState targetState);
}
