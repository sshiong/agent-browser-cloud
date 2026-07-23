package io.browsercloud.coordinator;

/**
 * Outbox 事件发布者接口。
 *
 * <p>使用 Transactional Outbox 模式保证事件发布与数据库操作的原子性。
 */
public interface OutboxPublisher {

  /**
   * 追加领域事件到 Outbox。
   *
   * <p>事件将在同一数据库事务中写入 outbox_events 表。
   *
   * @param event 领域事件
   */
  void append(DomainEvent event);
}
