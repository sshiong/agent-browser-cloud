package io.browsercloud.application;

import io.browsercloud.persistence.SessionResourcePolicyJpaRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Five-minute durable cost trend resolved from each Session's real active Placement. */
@Component
public class SessionResourceCostTrendScheduler {
  private static final Logger log =
      LoggerFactory.getLogger(SessionResourceCostTrendScheduler.class);

  private final SessionResourcePolicyJpaRepository policies;
  private final SessionResourceApplicationService resources;

  public SessionResourceCostTrendScheduler(
      SessionResourcePolicyJpaRepository policies, SessionResourceApplicationService resources) {
    this.policies = policies;
    this.resources = resources;
  }

  @Scheduled(fixedDelayString = "${resource-policy.cost-trend-interval-ms:300000}")
  public void aggregate() {
    policies
        .findDueCostEvaluation(Instant.now().minusSeconds(275), PageRequest.of(0, 500))
        .forEach(
            policy -> {
              try {
                resources.evaluateCostTrend(policy.getSessionId());
              } catch (RuntimeException failure) {
                log.warn(
                    "Session {} cost trend evaluation failed; Browser preserved",
                    policy.getSessionId(),
                    failure);
              }
            });
  }
}
