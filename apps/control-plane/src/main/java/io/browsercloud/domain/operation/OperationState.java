package io.browsercloud.domain.operation;

/** 操作状态。 */
public enum OperationState {
  /** 活跃状态 */
  ACTIVE,

  /** 已提交 */
  COMMITTED,

  /** 已中止 */
  ABORTED,

  /** 已超时 */
  TIMED_OUT
}
