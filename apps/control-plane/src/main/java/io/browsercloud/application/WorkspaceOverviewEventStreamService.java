package io.browsercloud.application;

import static io.browsercloud.api.WorkspaceOverviewModels.WorkspaceOverviewEvent;

import io.browsercloud.application.SessionResourceEventStreamService.ResourceStreamCapacityException;
import io.browsercloud.application.SessionResourceEventStreamService.ResourceStreamConnectionException;
import io.browsercloud.persistence.WorkspaceOverviewStreamStore;
import io.browsercloud.persistence.WorkspaceOverviewStreamStore.DurableWorkspaceChange;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
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

/** Cross-instance resumable Workspace Overview invalidation stream backed by PostgreSQL. */
@Service
public class WorkspaceOverviewEventStreamService {
  private static final Logger log =
      LoggerFactory.getLogger(WorkspaceOverviewEventStreamService.class);
  private static final int REPLAY_BATCH_SIZE = 500;
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

  private final WorkspaceOverviewStreamStore store;
  private final Map<ChannelKey, Channel> channels = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> tenantSubscriberCounts = new ConcurrentHashMap<>();
  private final AtomicInteger subscriberCount = new AtomicInteger();
  private final int maximumSubscribers;
  private final int maximumSubscribersPerTenant;
  private final long connectionTimeoutMillis;

  public WorkspaceOverviewEventStreamService(
      WorkspaceOverviewStreamStore store,
      @Value("${workspace-overview-stream.maximum-subscribers:500}") int maximumSubscribers,
      @Value("${workspace-overview-stream.maximum-subscribers-per-tenant:50}")
          int maximumSubscribersPerTenant,
      @Value("${workspace-overview-stream.connection-timeout-ms:1800000}")
          long connectionTimeoutMillis) {
    this.store = store;
    this.maximumSubscribers = Math.max(1, maximumSubscribers);
    this.maximumSubscribersPerTenant = Math.max(1, maximumSubscribersPerTenant);
    this.connectionTimeoutMillis = Math.max(30_000, connectionTimeoutMillis);
  }

  public SseEmitter subscribe(String tenantId, boolean includePlatformEvents, String lastEventId) {
    var key = new ChannelKey(tenantId, includePlatformEvents);
    var channel = channels.computeIfAbsent(key, ignored -> new Channel());
    synchronized (channel) {
      var latest = store.latestSequence(tenantId, includePlatformEvents);
      var requestedCursor = parseCursor(lastEventId);
      var resetRequired = requestedCursor != null && requestedCursor > latest;
      var cursor = requestedCursor == null || resetRequired ? latest : requestedCursor;
      var replayThrough = requestedCursor == null || resetRequired ? cursor : latest;
      if (!reserveSubscriber(tenantId)) {
        if (channel.subscribers.isEmpty()) channels.remove(key, channel);
        throw new ResourceStreamCapacityException();
      }
      var emitter = new SseEmitter(connectionTimeoutMillis);
      var subscriber = new Subscriber(emitter, cursor, replayThrough, Instant.now());
      channel.subscribers.add(subscriber);
      registerLifecycle(key, channel, subscriber);
      try {
        emitter.send(
            SseEmitter.event()
                .id(Long.toString(cursor))
                .name(
                    resetRequired
                        ? "workspace-overview-stream-reset"
                        : "workspace-overview-stream-ready")
                .reconnectTime(1_000)
                .data(
                    Map.of(
                        "cursor", cursor,
                        "resetRequired", resetRequired,
                        "connectedAt", Instant.now().toString())));
      } catch (IOException | IllegalStateException exception) {
        remove(key, channel, subscriber);
        throw new ResourceStreamConnectionException(exception);
      }
      publishChannel(key, channel);
      return emitter;
    }
  }

  @Scheduled(
      fixedDelayString = "${workspace-overview-stream.poll-interval-ms:1000}",
      scheduler = "resourceStreamTaskScheduler")
  public void publishDurableChanges() {
    channels.forEach(this::publishChannel);
  }

  private void publishChannel(ChannelKey key, Channel channel) {
    if (!channel.publishing.compareAndSet(false, true)) return;
    try {
      if (channel.subscribers.isEmpty()) {
        channels.remove(key, channel);
        return;
      }
      var after =
          channel.subscribers.stream()
              .filter(Subscriber::active)
              .mapToLong(Subscriber::cursor)
              .min()
              .orElse(Long.MAX_VALUE);
      if (after == Long.MAX_VALUE) {
        channels.remove(key, channel);
        return;
      }
      final java.util.List<DurableWorkspaceChange> changes;
      try {
        changes =
            store.readAfter(key.tenantId(), key.includePlatformEvents(), after, REPLAY_BATCH_SIZE);
      } catch (RuntimeException exception) {
        log.warn(
            "Workspace Overview stream source unavailable for tenant {}",
            key.tenantId(),
            exception);
        channel.subscribers.forEach(subscriber -> fail(key, channel, subscriber, exception));
        return;
      }
      if (changes.isEmpty()) {
        heartbeat(key, channel);
        return;
      }
      for (var change : changes) {
        channel.subscribers.forEach(
            subscriber -> {
              if (subscriber.active() && change.sequence() > subscriber.cursor()) {
                sendChange(key, channel, subscriber, change);
              }
            });
      }
    } finally {
      channel.publishing.set(false);
    }
  }

  private void sendChange(
      ChannelKey key, Channel channel, Subscriber subscriber, DurableWorkspaceChange change) {
    try {
      subscriber
          .emitter()
          .send(
              SseEmitter.event()
                  .id(Long.toString(change.sequence()))
                  .name("workspace-overview-change")
                  .data(
                      new WorkspaceOverviewEvent(
                          change.sequence(),
                          change.changeType(),
                          change.occurredAt(),
                          change.sequence() <= subscriber.replayThrough())));
      subscriber.advance(change.sequence(), Instant.now());
    } catch (IOException | IllegalStateException exception) {
      fail(key, channel, subscriber, exception);
    }
  }

  private void heartbeat(ChannelKey key, Channel channel) {
    var now = Instant.now();
    channel.subscribers.forEach(
        subscriber -> {
          if (!subscriber.active()
              || subscriber.lastWriteAt().plus(HEARTBEAT_INTERVAL).isAfter(now)) return;
          try {
            subscriber.emitter().send(SseEmitter.event().comment("workspace-overview-keepalive"));
            subscriber.wroteAt(now);
          } catch (IOException | IllegalStateException exception) {
            fail(key, channel, subscriber, exception);
          }
        });
  }

  private void registerLifecycle(ChannelKey key, Channel channel, Subscriber subscriber) {
    subscriber.emitter().onCompletion(() -> remove(key, channel, subscriber));
    subscriber.emitter().onTimeout(() -> remove(key, channel, subscriber));
    subscriber.emitter().onError(ignored -> remove(key, channel, subscriber));
  }

  private void fail(ChannelKey key, Channel channel, Subscriber subscriber, Throwable failure) {
    if (!subscriber.deactivate()) return;
    channel.subscribers.remove(subscriber);
    releaseSubscriber(key.tenantId());
    try {
      subscriber.emitter().completeWithError(failure);
    } catch (RuntimeException ignored) {
      // The servlet container may already have closed the async response.
    }
    if (channel.subscribers.isEmpty()) channels.remove(key, channel);
  }

  private void remove(ChannelKey key, Channel channel, Subscriber subscriber) {
    if (!subscriber.deactivate()) return;
    channel.subscribers.remove(subscriber);
    releaseSubscriber(key.tenantId());
    if (channel.subscribers.isEmpty()) channels.remove(key, channel);
  }

  static Long parseCursor(String lastEventId) {
    if (lastEventId == null || lastEventId.isBlank()) return null;
    try {
      var cursor = Long.parseLong(lastEventId.trim());
      if (cursor < 0) throw new IllegalArgumentException("Last-Event-ID must be non-negative");
      return cursor;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Last-Event-ID must be a numeric Workspace cursor");
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

  private record ChannelKey(String tenantId, boolean includePlatformEvents) {}

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
