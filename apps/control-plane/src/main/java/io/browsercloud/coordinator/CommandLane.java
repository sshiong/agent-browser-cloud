package io.browsercloud.coordinator;

/**
 * 命令通道。
 *
 * <p>Mailbox 不使用简单 FIFO，而是分为多个通道。
 */
public enum CommandLane {
  /** 关键控制：Browser Process Exit, OOM, Emergency Stop */
  CRITICAL(100),

  /** 交互：Human Input, Human Takeover, Agent Action Result */
  INTERACTIVE(80),

  /** 正常：State Event, Target Event */
  NORMAL(50),

  /** 维护：Snapshot, Hibernate, Extension Update */
  MAINTENANCE(20),

  /** 遥测：Metrics, Trace */
  TELEMETRY(10);

  private final int priority;

  CommandLane(int priority) {
    this.priority = priority;
  }

  public int priority() {
    return priority;
  }
}
