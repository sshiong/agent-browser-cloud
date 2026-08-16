package io.browsercloud.application;

import io.browsercloud.persistence.BrowserNodeFreshnessProjectionStore;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes only Browser Node heartbeat freshness transitions, never every heartbeat sample. */
@Service
public class BrowserNodeFreshnessProjector {
  private final BrowserNodeFreshnessProjectionStore store;
  private final Duration freshness;

  public BrowserNodeFreshnessProjector(
      BrowserNodeFreshnessProjectionStore store,
      @Value("${browser-node.freshness-threshold-ms:60000}") long freshnessThresholdMillis) {
    this.store = store;
    this.freshness = Duration.ofMillis(Math.max(10_000, freshnessThresholdMillis));
  }

  @Scheduled(
      fixedDelayString = "${browser-node.freshness-projection-interval-ms:5000}",
      scheduler = "resourceStreamTaskScheduler")
  @Transactional
  public void project() {
    var now = Instant.now();
    store.projectTransitions(now.minus(freshness), now);
  }
}
