package io.browsercloud.application;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reclaims abandoned Validation Worker leases without relying on an in-memory timer owner. */
@Component
public class RuntimeValidationQueueScheduler {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(RuntimeValidationQueueScheduler.class);
  private final RuntimeValidationQueueApplicationService queue;

  public RuntimeValidationQueueScheduler(RuntimeValidationQueueApplicationService queue) {
    this.queue = queue;
  }

  @Scheduled(fixedDelayString = "${enterprise.validation-worker.reaper-delay-ms:15000}")
  public void reclaimExpiredLeases() {
    try {
      var reclaimed = queue.expireLeases(Instant.now(), 100);
      if (reclaimed > 0) {
        LOGGER.warn("Reclaimed {} expired Runtime Validation Worker leases", reclaimed);
      }
    } catch (RuntimeException exception) {
      LOGGER.error("Runtime Validation Worker lease reaper failed", exception);
    }
  }
}
