package io.browsercloud.application;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Online capacity feedback with safety margin and hysteresis; security policy is never downgraded.
 */
@Service
public class CapacityAdmissionService {

  private final String coordinatorBuildId;
  private final int certifiedActors;
  private final int closePercent;
  private final int reopenPercent;
  private final AtomicInteger activeActors = new AtomicInteger();
  private volatile boolean admissionOpen = true;
  private volatile Instant updatedAt = Instant.now();

  public CapacityAdmissionService(
      @Value("${coordinator.build-id:control-plane-local}") String coordinatorBuildId,
      @Value("${coordinator.capacity.certified-active-actors:10000}") int certifiedActors,
      @Value("${coordinator.capacity.close-percent:85}") int closePercent,
      @Value("${coordinator.capacity.reopen-percent:70}") int reopenPercent) {
    if (certifiedActors < 1 || reopenPercent < 1 || closePercent <= reopenPercent) {
      throw new IllegalArgumentException("Coordinator capacity hysteresis is invalid");
    }
    this.coordinatorBuildId = coordinatorBuildId;
    this.certifiedActors = certifiedActors;
    this.closePercent = closePercent;
    this.reopenPercent = reopenPercent;
  }

  public synchronized Decision update(int currentActiveActors, boolean nodePressure) {
    activeActors.set(Math.max(0, currentActiveActors));
    var utilization = activeActors.get() * 100L / certifiedActors;
    if (nodePressure || utilization >= closePercent) {
      admissionOpen = false;
    } else if (utilization <= reopenPercent) {
      admissionOpen = true;
    }
    updatedAt = Instant.now();
    return snapshot();
  }

  public Decision snapshot() {
    return new Decision(
        admissionOpen,
        coordinatorBuildId,
        certifiedActors,
        activeActors.get(),
        closePercent,
        reopenPercent,
        updatedAt);
  }

  public record Decision(
      boolean admissionOpen,
      String coordinatorBuildId,
      int certifiedActiveActors,
      int activeActors,
      int closePercent,
      int reopenPercent,
      Instant updatedAt) {}
}
