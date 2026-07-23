package io.browsercloud.domain.workflow;

import java.util.Map;
import java.util.Set;

/**
 * 工作流状态机。
 *
 * <p>统一管理工作流状态转换，业务代码不能自由更新 state 字段。
 */
public final class WorkflowStateMachine {

  private static final Map<WorkflowState, Set<WorkflowState>> ALLOWED =
      Map.of(
          WorkflowState.PENDING,
          Set.of(WorkflowState.DISPATCHED, WorkflowState.CANCELLED),
          WorkflowState.DISPATCHED,
          Set.of(WorkflowState.RUNNING, WorkflowState.CANCELLED, WorkflowState.TIMED_OUT),
          WorkflowState.RUNNING,
          Set.of(
              WorkflowState.COMPLETING,
              WorkflowState.FAILED,
              WorkflowState.CANCELLED,
              WorkflowState.TIMED_OUT),
          WorkflowState.COMPLETING,
          Set.of(WorkflowState.COMPLETED, WorkflowState.FAILED, WorkflowState.TIMED_OUT),
          WorkflowState.FAILED,
          Set.of(WorkflowState.PENDING, WorkflowState.DEAD_LETTER),
          WorkflowState.TIMED_OUT,
          Set.of(WorkflowState.COMPENSATING, WorkflowState.DEAD_LETTER),
          WorkflowState.ORPHANED,
          Set.of(WorkflowState.COMPENSATING, WorkflowState.DEAD_LETTER),
          WorkflowState.COMPENSATING,
          Set.of(WorkflowState.COMPENSATED, WorkflowState.DEAD_LETTER));

  /**
   * 断言状态转换是否允许。
   *
   * @param from 源状态
   * @param to 目标状态
   * @throws IllegalStateException 如果转换不允许
   */
  public static void assertTransitionAllowed(WorkflowState from, WorkflowState to) {
    if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
      throw new IllegalStateException("Illegal workflow transition: " + from + " -> " + to);
    }
  }

  /**
   * 检查状态转换是否允许。
   *
   * @param from 源状态
   * @param to 目标状态
   * @return 是否允许
   */
  public static boolean isTransitionAllowed(WorkflowState from, WorkflowState to) {
    return ALLOWED.getOrDefault(from, Set.of()).contains(to);
  }
}
