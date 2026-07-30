package io.browsercloud.application;

import io.browsercloud.persistence.SessionResourcePolicyJpaRepository;
import org.springframework.stereotype.Service;

/** Executes one physically routed AUTO resource decision for a Session. */
@Service
public class SessionResourceDecisionExecutor {

  private final SessionResourcePolicyJpaRepository policies;
  private final SessionResourceApplicationService resources;
  private final SessionApplicationService sessions;
  private final SessionMigrationApplicationService migrations;

  public SessionResourceDecisionExecutor(
      SessionResourcePolicyJpaRepository policies,
      SessionResourceApplicationService resources,
      SessionApplicationService sessions,
      SessionMigrationApplicationService migrations) {
    this.policies = policies;
    this.resources = resources;
    this.sessions = sessions;
    this.migrations = migrations;
  }

  public void evaluate(String sessionId) {
    resources.evaluatePolicy(sessionId);
    policies
        .findById(sessionId)
        .ifPresent(
            evaluated -> {
              if ("SAFE_POINT_READY_MIGRATION_DISPATCH_PENDING"
                  .equals(evaluated.getStatusReason())) {
                migrations.request(evaluated.getSessionId(), evaluated.getTenantId());
              } else if ("SAFE_POINT_READY_HIBERNATE_DISPATCH_PENDING"
                  .equals(evaluated.getStatusReason())) {
                var operation =
                    migrations.hibernateAtSafePoint(
                        evaluated.getSessionId(), evaluated.getTenantId());
                resources.maximumActionDispatched(
                    evaluated.getSessionId(),
                    "HIBERNATE",
                    operation.operationId(),
                    "PENDING_NODE_CHECKPOINT");
              } else if ("MAXIMUM_REACHED_STRICT_TERMINATION_REQUIRED"
                  .equals(evaluated.getStatusReason())) {
                var operation =
                    sessions.terminateForResourcePolicy(
                        evaluated.getSessionId(), evaluated.getTenantId());
                resources.maximumActionDispatched(
                    evaluated.getSessionId(),
                    "TERMINATE_STRICT",
                    operation.operationId(),
                    "PENDING_NODE_STOP");
              }
            });
  }
}
