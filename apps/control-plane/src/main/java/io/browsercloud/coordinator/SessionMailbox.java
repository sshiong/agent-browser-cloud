package io.browsercloud.coordinator;

import java.util.concurrent.PriorityBlockingQueue;

/**
 * Session Mailbox。
 *
 * <p>使用优先级队列实现，MVP 阶段使用简单的 Priority Queue。 后续可替换为 Virtual Actor Runtime、Sharded Event Loop 或
 * Weighted Fair Queue。
 */
public class SessionMailbox {

  private static final int MAX_MESSAGES = 1000;
  private static final long MAX_BYTES = 1024 * 1024; // 1MB

  private final String sessionId;
  private final PriorityBlockingQueue<MailboxEntry> queue;
  private volatile long estimatedBytes;

  public SessionMailbox(String sessionId) {
    this.sessionId = sessionId;
    this.queue =
        new PriorityBlockingQueue<>(
            16, (a, b) -> Integer.compare(b.lane().priority(), a.lane().priority()));
    this.estimatedBytes = 0;
  }

  /**
   * 提交命令到邮箱。
   *
   * @param lane 命令通道
   * @param command 命令
   * @return 是否成功提交
   */
  public boolean offer(CommandLane lane, SessionCommand command) {
    if (queue.size() >= MAX_MESSAGES) {
      return false;
    }

    var entry = new MailboxEntry(lane, command, System.nanoTime());
    queue.offer(entry);
    return true;
  }

  /**
   * 取出下一个命令。
   *
   * @return 命令，如果邮箱为空则返回 null
   */
  public SessionCommand poll() {
    var entry = queue.poll();
    return entry != null ? entry.command() : null;
  }

  /** 获取邮箱大小。 */
  public int size() {
    return queue.size();
  }

  /** 估算字节数。 */
  public long estimatedBytes() {
    return estimatedBytes;
  }

  /** 检查邮箱是否为空。 */
  public boolean isEmpty() {
    return queue.isEmpty();
  }

  /** 邮箱条目。 */
  private record MailboxEntry(CommandLane lane, SessionCommand command, long enqueueTimeNanos) {}
}
