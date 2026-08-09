package io.browsercloud.application;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers expired GameDay leases and enforces bounded automatic abort deadlines. */
@Component
public class RecoveryGameDayQueueScheduler {

  private static final Logger log = LoggerFactory.getLogger(RecoveryGameDayQueueScheduler.class);
  private final RecoveryGameDayQueueApplicationService service;

  public RecoveryGameDayQueueScheduler(RecoveryGameDayQueueApplicationService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${enterprise.gameday-worker.reaper-interval-ms:15000}")
  public void reap() {
    try {
      var now = Instant.now();
      var deadlines = service.expireDeadlines(now, 100);
      var leases = service.expireLeases(now, 100);
      if (deadlines > 0 || leases > 0) {
        log.warn(
            "Recovery GameDay reaper requested {} aborts and recovered {} expired leases",
            deadlines,
            leases);
      }
    } catch (RuntimeException exception) {
      log.error("Recovery GameDay queue reaper failed", exception);
    }
  }
}
