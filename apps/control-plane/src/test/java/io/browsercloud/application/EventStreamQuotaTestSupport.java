package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Drives a servlet completion during tenant quota registration without timing sleeps. */
final class EventStreamQuotaTestSupport {
  private EventStreamQuotaTestSupport() {}

  static void replacementKeepsTenantLimit(Object service, Supplier<SseEmitter> subscribe)
      throws Exception {
    var counts = new PausingTenantCounts();
    ReflectionTestUtils.setField(service, "tenantSubscriberCounts", counts);
    var first = subscribe.get();
    SseEmitter replacement = null;
    var executor = Executors.newSingleThreadExecutor();
    try {
      counts.pauseNext.set(true);
      var registering = executor.submit(subscribe::get);
      assertThat(counts.registrationReached.await(5, TimeUnit.SECONDS)).isTrue();
      complete(first);
      counts.resumeRegistration.countDown();
      replacement = registering.get(5, TimeUnit.SECONDS);

      // The replacement is the only active connection; another must still be rejected.
      assertThatThrownBy(subscribe::get)
          .isInstanceOf(SessionResourceEventStreamService.ResourceStreamCapacityException.class);
      complete(replacement);
      replacement = null;
      var next = subscribe.get();
      complete(next);
    } finally {
      counts.resumeRegistration.countDown();
      complete(first);
      if (replacement != null) complete(replacement);
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void complete(SseEmitter emitter) {
    // This is the actual callback registered with the Spring servlet emitter, not a
    // direct call into the service's private quota or subscriber accounting methods.
    ((Runnable) ReflectionTestUtils.getField(emitter, "completionCallback")).run();
  }

  private static final class PausingTenantCounts extends ConcurrentHashMap<String, AtomicInteger> {
    private final AtomicBoolean pauseNext = new AtomicBoolean();
    private final CountDownLatch registrationReached = new CountDownLatch(1);
    private final CountDownLatch resumeRegistration = new CountDownLatch(1);

    @Override
    public AtomicInteger computeIfAbsent(
        String key, Function<? super String, ? extends AtomicInteger> mappingFunction) {
      var count = super.computeIfAbsent(key, mappingFunction);
      pause();
      return count;
    }

    @Override
    public AtomicInteger compute(
        String key,
        BiFunction<? super String, ? super AtomicInteger, ? extends AtomicInteger>
            remappingFunction) {
      pause();
      return super.compute(key, remappingFunction);
    }

    private void pause() {
      if (!pauseNext.compareAndSet(true, false)) return;
      registrationReached.countDown();
      try {
        if (!resumeRegistration.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test quota registration barrier timed out");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("test quota registration barrier interrupted", exception);
      }
    }
  }
}
