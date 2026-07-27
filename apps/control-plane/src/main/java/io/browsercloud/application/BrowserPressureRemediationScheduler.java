package io.browsercloud.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Critical PSI 的有界处置器。
 *
 * <p>Admission 已由 Heartbeat 同步关闭；这里每个 Tick 最多终止一个低优先级 Placement， 防止全节点同时 Stop 形成二次故障。Coordinator 的
 * Operation/Term fencing 仍是唯一执行边界。
 */
@Component
@ConditionalOnProperty(
    name = "browser-density.pressure-remediation.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BrowserPressureRemediationScheduler {

  private static final Logger log =
      LoggerFactory.getLogger(BrowserPressureRemediationScheduler.class);

  private final BrowserCapacityApplicationService capacityService;
  private final SessionResourceApplicationService resourceService;

  public BrowserPressureRemediationScheduler(
      BrowserCapacityApplicationService capacityService,
      SessionResourceApplicationService resourceService) {
    this.capacityService = capacityService;
    this.resourceService = resourceService;
  }

  @Scheduled(fixedDelayString = "${browser-density.pressure-remediation.interval-ms:1000}")
  public void remediateOne() {
    capacityService
        .claimPressureEviction()
        .ifPresent(
            candidate -> {
              try {
                resourceService.protectFromNodePressure(
                    candidate.sessionId(), candidate.tenantId(), candidate.nodeId());
                log.warn(
                    "Pressure protection requested for session {} on node {}",
                    candidate.sessionId(),
                    candidate.nodeId());
              } catch (RuntimeException exception) {
                capacityService.cancelPressureEviction(candidate.sessionId());
                log.debug(
                    "Pressure eviction deferred for session {}: {}",
                    candidate.sessionId(),
                    exception.getMessage());
              }
            });
  }
}
