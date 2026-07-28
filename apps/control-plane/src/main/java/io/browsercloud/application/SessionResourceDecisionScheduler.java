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
  private final SessionApplicationService sessionService;
  private final SessionMigrationApplicationService migrationService;

  public SessionResourceDecisionScheduler(
      SessionResourcePolicyJpaRepository policies,
      SessionResourceApplicationService service,
      SessionApplicationService sessionService,
      SessionMigrationApplicationService migrationService) {
    this.policies = policies;
    this.service = service;
    this.sessionService = sessionService;
    this.migrationService = migrationService;
  }

  @Scheduled(fixedDelayString = "${resource-policy.decision-interval-ms:30000}")
  public void evaluate() {
    policies
        .findDueActive(Instant.now().minusSeconds(25), PageRequest.of(0, 500))
        .forEach(
            policy -> {
              service.evaluatePolicy(policy.getSessionId());
              policies
                  .findById(policy.getSessionId())
                  .ifPresent(
                      evaluated -> {
                        if ("SAFE_POINT_READY_MIGRATION_DISPATCH_PENDING"
                            .equals(evaluated.getStatusReason())) {
                          migrationService.request(
                              evaluated.getSessionId(), evaluated.getTenantId());
                        } else if ("SAFE_POINT_READY_HIBERNATE_DISPATCH_PENDING"
                            .equals(evaluated.getStatusReason())) {
                          var operation =
                              migrationService.hibernateAtSafePoint(
                                  evaluated.getSessionId(), evaluated.getTenantId());
                          service.maximumActionDispatched(
                              evaluated.getSessionId(),
                              "HIBERNATE",
                              operation.operationId(),
                              "PENDING_NODE_CHECKPOINT");
                        } else if ("MAXIMUM_REACHED_STRICT_TERMINATION_REQUIRED"
                            .equals(evaluated.getStatusReason())) {
                          var operation =
                              sessionService.terminateForResourcePolicy(
                                  evaluated.getSessionId(), evaluated.getTenantId());
                          service.maximumActionDispatched(
                              evaluated.getSessionId(),
                              "TERMINATE_STRICT",
                              operation.operationId(),
                              "PENDING_NODE_STOP");
                        }
                      });
            });
  }
}
