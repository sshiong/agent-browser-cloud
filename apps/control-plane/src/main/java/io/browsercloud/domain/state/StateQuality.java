package io.browsercloud.domain.state;

/**
 * 状态质量枚举。
 *
 * <p>Agent 只有在状态质量满足 Action 要求时才能执行。 例如支付或数据修改要求 COMPLETE，普通滚动可接受 DEPTH_LIMITED。
 */
public enum StateQuality {
  /** 完整状态 */
  COMPLETE,

  /** 深度受限（大型页面） */
  DEPTH_LIMITED,

  /** 重新同步中 */
  RESYNCING,

  /** 降级状态 */
  DEGRADED,

  /** 无效状态（需要重新同步） */
  INVALID,

  /** 需要视觉辅助 */
  VISION_REQUIRED,

  /** 需要人工干预 */
  HUMAN_REQUIRED
}
