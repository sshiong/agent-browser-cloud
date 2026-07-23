package io.browsercloud.domain.operation;

/** 操作阶段。 */
public enum OperationPhase {
  /** 准备中 */
  PREPARING,

  /** 执行中 */
  EXECUTING,

  /** 刷写中（如 Profile Flush） */
  FLUSHING,

  /** 上传中 */
  UPLOADING,

  /** 验证中 */
  VERIFYING,

  /** 完成中 */
  COMPLETING
}
