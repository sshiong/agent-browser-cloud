package io.browsercloud.domain.session;

/**
 * 资源等级枚举。
 *
 * <p>定义 Browser Session 的资源配置等级。
 */
public enum ResourceClass {
  /** 无浏览器进程 */
  L0,

  /** Lite Production: 512~768MB, 2~4 Tabs */
  L1,

  /** Standard Agent: 768MB~1.25GB, 普通 SPA */
  L2,

  /** Desktop Interactive: 1GB~2GB+, noVNC 常驻 */
  L3,

  /** GPU: GPU/vGPU Node */
  L4,

  /** Native OS: Windows/macOS/Android Worker */
  L5
}
