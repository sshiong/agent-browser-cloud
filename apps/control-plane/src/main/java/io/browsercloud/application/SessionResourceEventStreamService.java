package io.browsercloud.application;

import static io.browsercloud.api.SessionResourceModels.ResourceStreamEventView;

import io.browsercloud.application.SessionResourceApplicationService.ResourcePolicyNotFoundException;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.persistence.SessionResourceStreamStore;
import io.browsercloud.persistence.SessionResourceStreamStore.DurableResourceChange;
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

/**
 * Cross-instance resumable SSE backed by the canonical PostgreSQL Session event envelope. The
 * in-memory registry only owns live sockets and is never a source of business state.
 */
@Service
public class SessionResourceEventStreamService {

  private static final Logger log =
      LoggerFactory.getLogger(SessionResourceEventStreamService.class);
  private static final int REPLAY_BATCH_SIZE = 500;
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

  private final SessionRepository sessions;
  private final SessionResourceStreamStore streamStore;
  private final Map<StreamKey, StreamChannel> channels = new ConcurrentHashMap<>();
  private final AtomicInteger subscriberCount = new AtomicInteger();
  private final int maximumSubscribers;
  private final int maximumSubscribersPerSession;
  private final long connectionTimeoutMillis;

  public SessionResourceEventStreamService(
      SessionRepository sessions,
      SessionResourceStreamStore streamStore,
      @Value("${resource-stream.maximum-subscribers:500}") int maximumSubscribers,
      @Value("${resource-stream.maximum-subscribers-per-session:20}")
          int maximumSubscribersPerSession,
      @Value("${resource-stream.connection-timeout-ms:1800000}") long connectionTimeoutMillis) {
    this.sessions = sessions;
    this.streamStore = streamStore;
    this.maximumSubscribers = Math.max(1, maximumSubscribers);
    this.maximumSubscribersPerSession = Math.max(1, maximumSubscribersPerSession);
    this.connectionTimeoutMillis = Math.max(30_000, connectionTimeoutMillis);
  }

  public SseEmitter subscribe(String sessionId, String tenantId, String lastEventId) {
    return subscribe(sessionId, tenantId, lastEventId, StreamProtocol.LEGACY_RESOURCE);
  }

  public SseEmitter subscribeSessionEvents(String sessionId, String tenantId, String lastEventId) {
    return subscribe(sessionId, tenantId, lastEventId, StreamProtocol.SESSION_EVENT);
  }

  private SseEmitter subscribe(
      String sessionId, String tenantId, String lastEventId, StreamProtocol protocol) {
    requireTenant(sessionId, tenantId);
    var key = new StreamKey(tenantId, sessionId);
    var channel = channels.computeIfAbsent(key, ignored -> new StreamChannel());
    synchronized (channel) {
      if (channel.subscribers.size() >= maximumSubscribersPerSession) {
        if (channel.subscribers.isEmpty()) channels.remove(key, channel);
        throw new ResourceStreamCapacityException();
      }

      var latest = streamStore.latestSequence(tenantId, sessionId);
      var requestedCursor = parseCursor(lastEventId);
      var resetRequired = requestedCursor != null && requestedCursor > latest;
      var cursor = requestedCursor == null || resetRequired ? latest : requestedCursor;
      var replayThrough = requestedCursor == null || resetRequired ? cursor : latest;
      if (!reserveSubscriber()) {
        if (channel.subscribers.isEmpty()) channels.remove(key, channel);
        throw new ResourceStreamCapacityException();
      }
      var emitter = new SseEmitter(connectionTimeoutMillis);
      var subscriber =
          new StreamSubscriber(emitter, cursor, replayThrough, Instant.now(), protocol);
      channel.subscribers.add(subscriber);
      registerLifecycle(key, channel, subscriber);
      try {
        emitter.send(
            SseEmitter.event()
                .id(Long.toString(cursor))
                .name(protocol.controlEventName(resetRequired))
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
      fixedDelayString = "${resource-stream.poll-interval-ms:1000}",
      scheduler = "resourceStreamTaskScheduler")
  public void publishDurableChanges() {
    channels.forEach(this::publishChannel);
  }

  private void publishChannel(StreamKey key, StreamChannel channel) {
    if (!channel.publishing.compareAndSet(false, true)) return;
    try {
      publishChannelOnce(key, channel);
    } finally {
      channel.publishing.set(false);
    }
  }

  private void publishChannelOnce(StreamKey key, StreamChannel channel) {
    if (channel.subscribers.isEmpty()) {
      channels.remove(key, channel);
      return;
    }
    var after =
        channel.subscribers.stream()
            .filter(StreamSubscriber::active)
            .mapToLong(StreamSubscriber::cursor)
            .min()
            .orElse(Long.MAX_VALUE);
    if (after == Long.MAX_VALUE) {
      channels.remove(key, channel);
      return;
    }
    final java.util.List<DurableResourceChange> changes;
    try {
      changes = streamStore.readAfter(key.tenantId(), key.sessionId(), after, REPLAY_BATCH_SIZE);
    } catch (RuntimeException exception) {
      log.warn("Session stream source unavailable for Session {}", key.sessionId(), exception);
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
  }

  private void sendChange(
      StreamKey key,
      StreamChannel channel,
      StreamSubscriber subscriber,
      DurableResourceChange change) {
    try {
      subscriber
          .emitter()
          .send(
              SseEmitter.event()
                  .id(Long.toString(change.sequence()))
                  .name(subscriber.protocol().changeEventName())
                  .data(
                      new ResourceStreamEventView(
                          change.sequence(),
                          change.changeType(),
                          change.entityId(),
                          change.occurredAt(),
                          change.sequence() <= subscriber.replayThrough())));
      subscriber.advance(change.sequence(), Instant.now());
    } catch (IOException | IllegalStateException exception) {
      fail(key, channel, subscriber, exception);
    }
  }

  private void heartbeat(StreamKey key, StreamChannel channel) {
    var now = Instant.now();
    channel.subscribers.forEach(
        subscriber -> {
          if (!subscriber.active()
              || subscriber.lastWriteAt().plus(HEARTBEAT_INTERVAL).isAfter(now)) {
            return;
          }
          try {
            subscriber.emitter().send(SseEmitter.event().comment("session-stream-keepalive"));
            subscriber.wroteAt(now);
          } catch (IOException | IllegalStateException exception) {
            fail(key, channel, subscriber, exception);
          }
        });
  }

  private void registerLifecycle(
      StreamKey key, StreamChannel channel, StreamSubscriber subscriber) {
    subscriber.emitter().onCompletion(() -> remove(key, channel, subscriber));
    subscriber.emitter().onTimeout(() -> remove(key, channel, subscriber));
    subscriber.emitter().onError(ignored -> remove(key, channel, subscriber));
  }

  private void fail(
      StreamKey key, StreamChannel channel, StreamSubscriber subscriber, Throwable failure) {
    if (!subscriber.deactivate()) return;
    channel.subscribers.remove(subscriber);
    subscriberCount.decrementAndGet();
    try {
      subscriber.emitter().completeWithError(failure);
    } catch (RuntimeException ignored) {
      // The servlet container may already have closed the async response.
    }
    if (channel.subscribers.isEmpty()) channels.remove(key, channel);
  }

  private void remove(StreamKey key, StreamChannel channel, StreamSubscriber subscriber) {
    if (!subscriber.deactivate()) return;
    channel.subscribers.remove(subscriber);
    subscriberCount.decrementAndGet();
    if (channel.subscribers.isEmpty()) channels.remove(key, channel);
  }

  private void requireTenant(String sessionId, String tenantId) {
    var session = sessions.require(sessionId);
    if (!tenantId.equals(session.tenantId())) throw new ResourcePolicyNotFoundException();
  }

  static Long parseCursor(String lastEventId) {
    if (lastEventId == null || lastEventId.isBlank()) return null;
    try {
      var cursor = Long.parseLong(lastEventId.trim());
      if (cursor < 0) throw new IllegalArgumentException("Last-Event-ID must be non-negative");
      return cursor;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Last-Event-ID must be a numeric Session cursor");
    }
  }

  int activeSubscriberCount() {
    return subscriberCount.get();
  }

  private boolean reserveSubscriber() {
    int current;
    do {
      current = subscriberCount.get();
      if (current >= maximumSubscribers) return false;
    } while (!subscriberCount.compareAndSet(current, current + 1));
    return true;
  }

  private record StreamKey(String tenantId, String sessionId) {}

  private enum StreamProtocol {
    LEGACY_RESOURCE {
      @Override
      String controlEventName(boolean resetRequired) {
        return resetRequired ? "resource-stream-reset" : "resource-stream-ready";
      }

      @Override
      String changeEventName() {
        return "session-resource-change";
      }
    },
    SESSION_EVENT {
      @Override
      String controlEventName(boolean resetRequired) {
        return resetRequired ? "session-stream-reset" : "session-stream-ready";
      }

      @Override
      String changeEventName() {
        return "session-change";
      }
    };

    abstract String controlEventName(boolean resetRequired);

    abstract String changeEventName();
  }

  private static final class StreamChannel {
    private final CopyOnWriteArrayList<StreamSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean publishing = new AtomicBoolean();
  }

  private static final class StreamSubscriber {
    private final SseEmitter emitter;
    private final AtomicLong cursor;
    private final long replayThrough;
    private final StreamProtocol protocol;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private volatile Instant lastWriteAt;

    private StreamSubscriber(
        SseEmitter emitter,
        long cursor,
        long replayThrough,
        Instant lastWriteAt,
        StreamProtocol protocol) {
      this.emitter = emitter;
      this.cursor = new AtomicLong(cursor);
      this.replayThrough = replayThrough;
      this.lastWriteAt = lastWriteAt;
      this.protocol = protocol;
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

    private StreamProtocol protocol() {
      return protocol;
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

  public static final class ResourceStreamCapacityException extends RuntimeException {}

  public static final class ResourceStreamConnectionException extends RuntimeException {
    private ResourceStreamConnectionException(Throwable cause) {
      super(cause);
    }
  }
}
