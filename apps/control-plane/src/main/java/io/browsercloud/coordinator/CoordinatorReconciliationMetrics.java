package io.browsercloud.coordinator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import java.util.function.Supplier;

/** Coordinator 换主对账的低基数运行指标。 */
public final class CoordinatorReconciliationMetrics {

  static final String DURATION = "browsercloud.coordinator.reconcile.duration";
  static final String STALE_ABORTED = "browsercloud.coordinator.reconcile.stale.operations.aborted";
  static final String CLEANUP_STARTED = "browsercloud.coordinator.reconcile.cleanup.started";
  static final String CLEANUP_FAILURES = "browsercloud.coordinator.reconcile.cleanup.failures";

  private final Timer duration;
  private final Counter staleOperationsAborted;
  private final Counter cleanupStarted;
  private final Counter cleanupFailures;

  public CoordinatorReconciliationMetrics(MeterRegistry registry) {
    duration =
        Timer.builder(DURATION)
            .description("Coordinator ownership reconciliation latency")
            .publishPercentileHistogram()
            .register(registry);
    staleOperationsAborted =
        Counter.builder(STALE_ABORTED)
            .description("Stale active operations aborted after coordinator failover")
            .register(registry);
    cleanupStarted =
        Counter.builder(CLEANUP_STARTED)
            .description("New-term runtime cleanup operations started after failover")
            .register(registry);
    cleanupFailures =
        Counter.builder(CLEANUP_FAILURES)
            .description("Failures while creating or dispatching new-term runtime cleanup")
            .register(registry);
  }

  Optional<CoordinatorResult> record(Supplier<Optional<CoordinatorResult>> reconciliation) {
    return duration.record(reconciliation);
  }

  void staleOperationAborted() {
    staleOperationsAborted.increment();
  }

  void cleanupStarted() {
    cleanupStarted.increment();
  }

  void cleanupFailed() {
    cleanupFailures.increment();
  }
}
