package io.browsercloud.application;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * PostgreSQL-backed wakeups for bounded Worker claim long-polls. Queue rows remain authoritative.
 */
@Service
public final class WorkerQueueWakeupService {

  public static final String AGENT_EXECUTION = "agent-execution";
  public static final String AGENT_REVIEW = "agent-review";
  public static final String AGENT_OUTCOME = "agent-outcome";
  public static final String CHALLENGE_VISUAL = "challenge-visual";
  public static final String RUNTIME_VALIDATION = "runtime-validation";
  public static final String RECOVERY_GAMEDAY = "recovery-gameday";
  public static final int MAX_WAIT_SECONDS = 25;

  private static final Logger LOG = LoggerFactory.getLogger(WorkerQueueWakeupService.class);
  private static final String CHANNEL = "browsercloud_worker_jobs";

  private final DataSource dataSource;
  private final long notificationPollMillis;
  private final AtomicBoolean running = new AtomicBoolean();
  private final Map<String, AtomicLong> generations = new ConcurrentHashMap<>();
  private final Map<String, ConcurrentLinkedQueue<CompletableFuture<Void>>> waiters =
      new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("worker-claim-timeout", 0).factory());
  private final ExecutorService claimExecutor =
      Executors.newFixedThreadPool(
          16, Thread.ofPlatform().name("worker-claim-completion-", 0).factory());
  private volatile Thread listenerThread;
  private volatile Connection listenerConnection;

  public WorkerQueueWakeupService(
      DataSource dataSource,
      @Value("${worker.claim-notification-poll-millis:10000}") long notificationPollMillis) {
    this.dataSource = dataSource;
    this.notificationPollMillis = Math.max(1_000, Math.min(30_000, notificationPollMillis));
  }

  @PostConstruct
  void start() {
    if (!running.compareAndSet(false, true)) return;
    listenerThread =
        Thread.ofPlatform().name("worker-queue-listener").daemon(true).start(this::listen);
  }

  @PreDestroy
  void stop() {
    running.set(false);
    closeListenerConnection();
    if (listenerThread != null) listenerThread.interrupt();
    signalAll();
    scheduler.shutdownNow();
    claimExecutor.shutdownNow();
  }

  public <T> CompletableFuture<T> claim(
      String queue, int waitSeconds, Supplier<T> claim, java.util.function.Predicate<T> found) {
    var boundedWait = Math.max(0, Math.min(MAX_WAIT_SECONDS, waitSeconds));
    var generation = generationCounter(queue).get();
    var immediate = claim.get();
    if (found.test(immediate) || boundedWait == 0) {
      return CompletableFuture.completedFuture(immediate);
    }
    return await(queue, generation, Duration.ofSeconds(boundedWait))
        .thenApplyAsync(ignored -> claim.get(), claimExecutor);
  }

  long generation(String queue) {
    return generationCounter(queue).get();
  }

  CompletableFuture<Void> await(String queue, long observedGeneration, Duration timeout) {
    if (generationCounter(queue).get() != observedGeneration) {
      return CompletableFuture.completedFuture(null);
    }
    var future = new CompletableFuture<Void>();
    var queueWaiters = waiters.computeIfAbsent(queue, ignored -> new ConcurrentLinkedQueue<>());
    queueWaiters.add(future);
    if (generationCounter(queue).get() != observedGeneration && queueWaiters.remove(future)) {
      future.complete(null);
    }
    scheduler.schedule(
        () -> {
          if (queueWaiters.remove(future)) future.complete(null);
        },
        Math.max(1, timeout.toMillis()),
        java.util.concurrent.TimeUnit.MILLISECONDS);
    return future;
  }

  void signal(String queue) {
    generationCounter(queue).incrementAndGet();
    var queueWaiters = waiters.get(queue);
    if (queueWaiters == null) return;
    CompletableFuture<Void> waiter;
    while ((waiter = queueWaiters.poll()) != null) {
      if (waiter.complete(null)) return;
    }
  }

  private AtomicLong generationCounter(String queue) {
    return generations.computeIfAbsent(queue, ignored -> new AtomicLong());
  }

  private void listen() {
    while (running.get()) {
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        listenerConnection = connection;
        connection.setAutoCommit(true);
        statement.execute("LISTEN " + CHANNEL);
        signalAll();
        var postgres = connection.unwrap(PGConnection.class);
        while (running.get() && !connection.isClosed()) {
          var notifications = postgres.getNotifications((int) notificationPollMillis);
          if (notifications == null) continue;
          for (var notification : notifications) {
            var payload = notification.getParameter();
            var separator = payload.indexOf(':');
            signal(separator < 0 ? payload : payload.substring(0, separator));
          }
        }
      } catch (Exception exception) {
        if (running.get()) {
          LOG.warn(
              "Worker queue notification listener reconnecting: {}",
              exception.getClass().getSimpleName());
          signalAll();
          try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(500, 2_001));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      } finally {
        listenerConnection = null;
      }
    }
  }

  private void signalAll() {
    waiters.forEach(
        (queue, queueWaiters) -> {
          generationCounter(queue).incrementAndGet();
          CompletableFuture<Void> waiter;
          while ((waiter = queueWaiters.poll()) != null) waiter.complete(null);
        });
  }

  private void closeListenerConnection() {
    var connection = listenerConnection;
    if (connection == null) return;
    try {
      connection.close();
    } catch (Exception ignored) {
      // Shutdown is best-effort; queue correctness is preserved by bounded long-poll timeouts.
    }
  }
}
