package io.browsercloud.domain.workflow;

/**
 * 工作流状态枚举。
 *
 * <p>状态转换矩阵：
 *
 * <pre>
 * Pending → Dispatched → Running → Completing → Completed
 *                ↓           ↓          ↓
 *            Cancelled    Failed    TimedOut
 *                            ↓          ↓
 *                        DeadLetter  Compensating → Compensated → DeadLetter
 * </pre>
 */
public enum WorkflowState {
  /** 等待派发 */
  PENDING,

  /** 已派发 */
  DISPATCHED,

  /** 运行中 */
  RUNNING,

  /** 完成中（正在执行最终 CAS/Commit Marker） */
  COMPLETING,

  /** 已完成（不可变） */
  COMPLETED,

  /** 失败 */
  FAILED,

  /** 已取消（不可变） */
  CANCELLED,

  /** 已超时 */
  TIMED_OUT,

  /** 孤儿（Worker 失联） */
  ORPHANED,

  /** 补偿中 */
  COMPENSATING,

  /** 已补偿（不可变） */
  COMPENSATED,

  /** 死信（需要人工处理） */
  DEAD_LETTER
}
