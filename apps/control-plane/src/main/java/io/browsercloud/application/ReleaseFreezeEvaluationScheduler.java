package io.browsercloud.application;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 多 Control Plane 下按 Tenant 行锁串行评估 Release Freeze。 */
@Component
public class ReleaseFreezeEvaluationScheduler {
  private static final Logger log = LoggerFactory.getLogger(ReleaseFreezeEvaluationScheduler.class);
  private static final int PAGE_SIZE = 200;
  private static final int MAX_TENANTS_PER_RUN = 10_000;

  private final ReleaseFreezeApplicationService service;

  public ReleaseFreezeEvaluationScheduler(ReleaseFreezeApplicationService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${enterprise.release-freeze-evaluation-interval-ms:30000}")
  public void evaluate() {
    var after = "";
    var processed = 0;
    while (processed < MAX_TENANTS_PER_RUN) {
      var tenants = service.enabledTenantsAfter(after, PAGE_SIZE);
      if (tenants.isEmpty()) return;
      for (var tenantId : tenants) {
        try {
          service.evaluateTenant(tenantId, Instant.now());
        } catch (RuntimeException exception) {
          log.warn("Release Freeze evaluation failed for tenant {}", tenantId, exception);
        }
        processed++;
        if (processed >= MAX_TENANTS_PER_RUN) return;
      }
      after = tenants.getLast();
      if (tenants.size() < PAGE_SIZE) return;
    }
  }
}
