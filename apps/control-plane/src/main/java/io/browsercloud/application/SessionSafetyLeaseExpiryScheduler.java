package io.browsercloud.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Converts naturally expired application leases into durable, SSE-visible terminal events. */
@Component
public class SessionSafetyLeaseExpiryScheduler {

  private final SessionSafetyLeaseApplicationService leases;

  public SessionSafetyLeaseExpiryScheduler(SessionSafetyLeaseApplicationService leases) {
    this.leases = leases;
  }

  @Scheduled(fixedDelayString = "${safe-point.application-lease-expiry-interval-ms:1000}")
  public void expire() {
    leases.expireDueLeases();
  }
}
