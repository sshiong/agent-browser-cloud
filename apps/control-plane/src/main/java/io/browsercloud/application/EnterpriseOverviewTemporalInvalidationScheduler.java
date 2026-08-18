package io.browsercloud.application;

import io.browsercloud.persistence.EnterpriseOverviewStreamStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes durable invalidations when time-windowed Enterprise aggregates change. */
@Component
public class EnterpriseOverviewTemporalInvalidationScheduler {
  private static final Logger log =
      LoggerFactory.getLogger(EnterpriseOverviewTemporalInvalidationScheduler.class);
  private static final int BATCH_SIZE = 1_000;

  private final EnterpriseOverviewStreamStore store;

  public EnterpriseOverviewTemporalInvalidationScheduler(EnterpriseOverviewStreamStore store) {
    this.store = store;
  }

  @Scheduled(fixedDelayString = "${enterprise-overview-stream.expiry-poll-interval-ms:1000}")
  public void publishDueInvalidations() {
    try {
      for (int batch = 0; batch < 10; batch++) {
        if (store.publishDueInvalidations(BATCH_SIZE) < BATCH_SIZE) return;
      }
    } catch (RuntimeException exception) {
      log.warn("Enterprise Overview scheduled invalidation source unavailable", exception);
    }
  }
}
