package io.browsercloud.coordinator;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.PriorityQueue;

/** Bounded mailbox: emergency and human control cannot be starved by telemetry. */
public final class VirtualActorMailbox<T> {

  private final int maximumEntries;
  private final PriorityQueue<Entry<T>> queue =
      new PriorityQueue<>(
          Comparator.<Entry<T>>comparingInt(Entry::priority)
              .reversed()
              .thenComparingLong(Entry::sequence));
  private long sequence;
  private Instant lastActivity = Instant.now();

  public VirtualActorMailbox(int maximumEntries) {
    if (maximumEntries < 1) {
      throw new IllegalArgumentException("maximumEntries must be positive");
    }
    this.maximumEntries = maximumEntries;
  }

  public synchronized boolean offer(T message, int priority) {
    if (queue.size() >= maximumEntries && priority < 90) {
      return false;
    }
    if (queue.size() >= maximumEntries) {
      queue.stream().min(Comparator.comparingInt(Entry::priority)).ifPresent(queue::remove);
    }
    queue.add(new Entry<>(message, priority, sequence++));
    lastActivity = Instant.now();
    return true;
  }

  public synchronized Optional<T> poll() {
    var entry = queue.poll();
    lastActivity = Instant.now();
    return Optional.ofNullable(entry).map(Entry::message);
  }

  public synchronized boolean canPassivate(Instant now, long idleSeconds) {
    return queue.isEmpty() && !lastActivity.plusSeconds(idleSeconds).isAfter(now);
  }

  public synchronized int size() {
    return queue.size();
  }

  private record Entry<T>(T message, int priority, long sequence) {}
}
