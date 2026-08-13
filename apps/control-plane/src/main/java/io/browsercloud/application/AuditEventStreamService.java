package io.browsercloud.application;

import static io.browsercloud.api.AuditEventStreamModels.AuditEventStreamChange;
import static io.browsercloud.api.AuditEventStreamModels.AuditEventStreamControl;

import io.browsercloud.application.SessionResourceEventStreamService.ResourceStreamCapacityException;
import io.browsercloud.application.SessionResourceEventStreamService.ResourceStreamConnectionException;
import io.browsercloud.persistence.AuditEventStreamStore;
import io.browsercloud.persistence.AuditEventStreamStore.DurableAuditChange;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Tenant-scoped resumable audit invalidations backed by the immutable audit chain sequence.
 *
 * <p>The notification stream only advances on high-signal rows, so the full audit ledger needs its
 * own cursor: every audited row advances this one, which is what lets the console drop fixed
 * polling without risking a permanently stale list.
 */
@Service
public class AuditEventStreamService {
  private static final Logger log = LoggerFactory.getLogger(AuditEventStreamService.class);
  private static final int REPLAY_BATCH_SIZE = 500;
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

  private final AuditEventStreamStore store;
  private final Map<String, Channel> channels = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> tenantSubscriberCounts = new ConcurrentHashMap<>();
  private final AtomicInteger subscriberCount = new AtomicInteger();
  private final int maximumSubscribers;
  private final int maximumSubscribersPerTenant;
  private final long connectionTimeoutMillis;

  public AuditEventStreamService(
      AuditEventStreamStore store,
      @Value("${audit-stream.maximum-subscribers:500}") int maximumSubscribers,
      @Value("${audit-stream.maximum-subscribers-per-tenant:50}") int maximumSubscribersPerTenant,
      @Value("${audit-stream.connection-timeout-ms:1800000}") long connectionTimeoutMillis) {
    this.store = store;
    this.maximumSubscribers = Math.max(1, maximumSubscribers);
    this.maximumSubscribersPerTenant = Math.max(1, maximumSubscribersPerTenant);
    this.connectionTimeoutMillis = Math.max(30_000, connectionTimeoutMillis);
  }

  public SseEmitter subscribe(String tenantId, String lastEventId) {
    var channel = channels.computeIfAbsent(tenantId, ignored -> new Channel());
    synchronized (channel) {
      var latest = store.latestSequence(tenantId);
      var requestedCursor = parseCursor(lastEventId);
      var resetRequired = requestedCursor != null && requestedCursor > latest;
      var cursor = requestedCursor == null || resetRequired ? latest : requestedCursor;
      var replayThrough = requestedCursor == null || resetRequired ? cursor : latest;
      if (!reserveSubscriber(tenantId)) {
        if (channel.subscribers.isEmpty()) channels.remove(tenantId, channel);
        throw new ResourceStreamCapacityException();
      }
      var emitter = new SseEmitter(connectionTimeoutMillis);
      var subscriber = new Subscriber(emitter, cursor, replayThrough, Instant.now());
      channel.subscribers.add(subscriber);
      registerLifecycle(tenantId, channel, subscriber);
      try {
        emitter.send(
            SseEmitter.event()
                .id(Long.toString(cursor))
                .name(resetRequired ? "audit-stream-reset" : "audit-stream-ready")
                .reconnectTime(1_000)
                .data(new AuditEventStreamControl(cursor, resetRequired, Instant.now())));
      } catch (IOException | IllegalStateException exception) {
        remove(tenantId, channel, subscriber);
        throw new ResourceStreamConnectionException(exception);
      }
      publishChannel(tenantId, channel);
      return emitter;
    }
  }

  @Scheduled(
      fixedDelayString = "${audit-stream.poll-interval-ms:1000}",
      scheduler = "resourceStreamTaskScheduler")
  public void publishDurableChanges() {
    channels.forEach(this::publishChannel);
  }

  private void publishChannel(String tenantId, Channel channel) {
    if (!channel.publishing.compareAndSet(false, true)) return;
    try {
      if (channel.subscribers.isEmpty()) {
        channels.remove(tenantId, channel);
        return;
      }
      var after =
          channel.subscribers.stream()
              .filter(Subscriber::active)
              .mapToLong(Subscriber::cursor)
              .min()
              .orElse(Long.MAX_VALUE);
      if (after == Long.MAX_VALUE) {
        channels.remove(tenantId, channel);
        return;
      }
      final List<DurableAuditChange> changes;
      try {
        changes = store.readAfter(tenantId, after, REPLAY_BATCH_SIZE);
      } catch (RuntimeException exception) {
        log.warn("Audit event stream source unavailable for tenant {}", tenantId, exception);
        channel.subscribers.forEach(subscriber -> fail(tenantId, channel, subscriber, exception));
        return;
      }
      if (changes.isEmpty()) {
        heartbeat(tenantId, channel);
        return;
      }
      for (var change : changes) {
        channel.subscribers.forEach(
            subscriber -> {
              if (subscriber.active() && change.sequence() > subscriber.cursor()) {
                sendChange(tenantId, channel, subscriber, change);
              }
            });
      }
    } finally {
      channel.publishing.set(false);
    }
  }

  private void sendChange(
      String tenantId, Channel channel, Subscriber subscriber, DurableAuditChange change) {
    try {
      subscriber
          .emitter()
          .send(
              SseEmitter.event()
                  .id(Long.toString(change.sequence()))
                  .name("audit-change")
                  .data(
                      new AuditEventStreamChange(
                          change.sequence(),
                          change.occurredAt(),
                          change.sequence() <= subscriber.replayThrough())));
      subscriber.advance(change.sequence(), Instant.now());
    } catch (IOException | IllegalStateException exception) {
      fail(tenantId, channel, subscriber, exception);
    }
  }

  private void heartbeat(String tenantId, Channel channel) {
    var now = Instant.now();
    channel.subscribers.forEach(
        subscriber -> {
          if (!subscriber.active()
              || subscriber.lastWriteAt().plus(HEARTBEAT_INTERVAL).isAfter(now)) return;
          try {
            subscriber.emitter().send(SseEmitter.event().comment("audit-keepalive"));
            subscriber.wroteAt(now);
          } catch (IOException | IllegalStateException exception) {
            fail(tenantId, channel, subscriber, exception);
          }
        });
  }

  private void registerLifecycle(String tenantId, Channel channel, Subscriber subscriber) {
    subscriber.emitter().onCompletion(() -> remove(tenantId, channel, subscriber));
    subscriber.emitter().onTimeout(() -> remove(tenantId, channel, subscriber));
    subscriber.emitter().onError(ignored -> remove(tenantId, channel, subscriber));
  }

  private void fail(String tenantId, Channel channel, Subscriber subscriber, Throwable failure) {
    if (!subscriber.deactivate()) return;
    channel.subscribers.remove(subscriber);
    releaseSubscriber(tenantId);
    try {
      subscriber.emitter().completeWithError(failure);
    } catch (RuntimeException ignored) {
      // The servlet container may already have closed the async response.
    }
    if (channel.subscribers.isEmpty()) channels.remove(tenantId, channel);
  }

  private void remove(String tenantId, Channel channel, Subscriber subscriber) {
    if (!subscriber.deactivate()) return;
    channel.subscribers.remove(subscriber);
    releaseSubscriber(tenantId);
    if (channel.subscribers.isEmpty()) channels.remove(tenantId, channel);
  }

  static Long parseCursor(String lastEventId) {
    if (lastEventId == null || lastEventId.isBlank()) return null;
    try {
      var cursor = Long.parseLong(lastEventId.trim());
      if (cursor < 0) throw new IllegalArgumentException("Last-Event-ID must be non-negative");
      return cursor;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Last-Event-ID must be a numeric audit cursor");
    }
  }

  int activeSubscriberCount() {
    return subscriberCount.get();
  }

  private boolean reserveSubscriber(String tenantId) {
    int current;
    do {
      current = subscriberCount.get();
      if (current >= maximumSubscribers) return false;
    } while (!subscriberCount.compareAndSet(current, current + 1));
    var tenantCount =
        tenantSubscriberCounts.computeIfAbsent(tenantId, ignored -> new AtomicInteger());
    int tenantCurrent;
    do {
      tenantCurrent = tenantCount.get();
      if (tenantCurrent >= maximumSubscribersPerTenant) {
        subscriberCount.decrementAndGet();
        if (tenantCount.get() == 0) tenantSubscriberCounts.remove(tenantId, tenantCount);
        return false;
      }
    } while (!tenantCount.compareAndSet(tenantCurrent, tenantCurrent + 1));
    return true;
  }

  private void releaseSubscriber(String tenantId) {
    subscriberCount.decrementAndGet();
    var tenantCount = tenantSubscriberCounts.get(tenantId);
    if (tenantCount != null && tenantCount.decrementAndGet() == 0) {
      tenantSubscriberCounts.remove(tenantId, tenantCount);
    }
  }

  private static final class Channel {
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean publishing = new AtomicBoolean();
  }

  private static final class Subscriber {
    private final SseEmitter emitter;
    private final AtomicLong cursor;
    private final long replayThrough;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private volatile Instant lastWriteAt;

    private Subscriber(SseEmitter emitter, long cursor, long replayThrough, Instant lastWriteAt) {
      this.emitter = emitter;
      this.cursor = new AtomicLong(cursor);
      this.replayThrough = replayThrough;
      this.lastWriteAt = lastWriteAt;
    }

    private SseEmitter emitter() {
      return emitter;
    }

    private long cursor() {
      return cursor.get();
    }

    private long replayThrough() {
      return replayThrough;
    }

    private boolean active() {
      return active.get();
    }

    private boolean deactivate() {
      return active.compareAndSet(true, false);
    }

    private Instant lastWriteAt() {
      return lastWriteAt;
    }

    private void advance(long sequence, Instant now) {
      cursor.set(sequence);
      lastWriteAt = now;
    }

    private void wroteAt(Instant now) {
      lastWriteAt = now;
    }
  }
}
