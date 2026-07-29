package io.browsercloud.domain.operation;

/**
 * 操作模式。
 *
 * <p>定义 ExclusiveOperation 的具体操作类型。
 */
public enum OperationMode {
  /** Agent 交互式操作 */
  AGENT_INTERACTIVE,

  /** 人工接管 */
  HUMAN_TAKEOVER,

  /** 人工辅助 */
  HUMAN_ASSIST,

  /** 静默（用于 Snapshot 等） */
  QUIESCE,

  /** 快照 */
  SNAPSHOT,

  /** 休眠 */
  HIBERNATE,

  /** 恢复 */
  RECOVERY,

  /** 代理切换 */
  PROXY_TRANSITION,

  /** 扩展维护 */
  EXTENSION_MAINTENANCE,

  /** 资源策略或资源调整 */
  RESOURCE_ADJUSTMENT,

  /** Session 应用恢复契约绑定升级 */
  APPLICATION_BINDING,

  /** 终止 */
  TERMINATION
}
