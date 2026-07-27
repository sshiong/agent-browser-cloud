package io.browsercloud.application;

import io.browsercloud.persistence.SessionResourcePolicyJpaRepository;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 30-second aggregate decision loop. Five-second collection is owned by Browser Node. */
@Component
public class SessionResourceDecisionScheduler {
  private final SessionResourcePolicyJpaRepository policies;
  private final SessionResourceApplicationService service;

  public SessionResourceDecisionScheduler(
      SessionResourcePolicyJpaRepository policies, SessionResourceApplicationService service) {
    this.policies = policies;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${resource-policy.decision-interval-ms:30000}")
  public void evaluate() {
    policies
        .findDueActive(Instant.now().minusSeconds(25), PageRequest.of(0, 500))
        .forEach(policy -> service.evaluatePolicy(policy.getSessionId()));
  }
}
