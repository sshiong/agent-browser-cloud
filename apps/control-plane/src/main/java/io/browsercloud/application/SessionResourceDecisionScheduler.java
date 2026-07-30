package io.browsercloud.application;

import static io.browsercloud.application.CoordinatorCommandPayloads.*;

import io.browsercloud.persistence.SessionResourcePolicyJpaRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 30-second aggregate decision loop. Five-second collection is owned by Browser Node. */
@Component
public class SessionResourceDecisionScheduler {
  private final SessionResourcePolicyJpaRepository policies;
  private final CoordinatorCommandRoutingService commandRouting;

  public SessionResourceDecisionScheduler(
      SessionResourcePolicyJpaRepository policies,
      CoordinatorCommandRoutingService commandRouting) {
    this.policies = policies;
    this.commandRouting = commandRouting;
  }

  @Scheduled(fixedDelayString = "${resource-policy.decision-interval-ms:30000}")
  public void evaluate() {
    var now = Instant.now();
    var decisionBucket = now.getEpochSecond() / 25;
    policies
        .findDueActive(now.minusSeconds(25), PageRequest.of(0, 500))
        .forEach(
            policy ->
                commandRouting.enqueueAsync(
                    policy.getSessionId(),
                    RESOURCE_POLICY_EVALUATE,
                    "resource-evaluate:" + policy.getSessionId() + ":" + decisionBucket,
                    new ResourceEvaluation(policy.getTenantId()),
                    Duration.ofMinutes(2)));
  }
}
