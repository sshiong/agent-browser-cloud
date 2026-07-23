package io.browsercloud.domain.session;

/**
 * Session 状态枚举。
 *
 * <p>状态转换路径：
 *
 * <pre>
 * CREATED → STARTING → RUNNING → TERMINATING → TERMINATED
 *                    ↓         ↓
 *                 DEGRADED   HIBERNATING → HIBERNATED → RECOVERING
 *                    ↓
 *                  FAILED
 * </pre>
 */
public enum SessionState {
  /** 已创建，未启动 */
  CREATED,

  /** 正在启动 Runtime */
  STARTING,

  /** 正常运行 */
  RUNNING,

  /** 降级运行（如 Browser Crash 后恢复中） */
  DEGRADED,

  /** 正在休眠 */
  HIBERNATING,

  /** 已休眠 */
  HIBERNATED,

  /** 正在恢复 */
  RECOVERING,

  /** 正在终止 */
  TERMINATING,

  /** 已终止 */
  TERMINATED,

  /** 失败 */
  FAILED
}
