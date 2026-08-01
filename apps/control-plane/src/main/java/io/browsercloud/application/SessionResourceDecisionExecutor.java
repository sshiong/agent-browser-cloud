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
    if (dispatchPending(sessionId)) {
      return;
    }
    resources.evaluatePolicy(sessionId);
    dispatchPending(sessionId);
  }

  /** Dispatches persisted protection decisions; safe to retry from telemetry and the scheduler. */
  public boolean dispatchPending(String sessionId) {
    var dispatched = new boolean[] {false};
    policies
        .findById(sessionId)
        .ifPresent(
            evaluated -> {
              if ("SAFE_POINT_READY_MIGRATION_DISPATCH_PENDING"
                  .equals(evaluated.getStatusReason())) {
                migrations.request(evaluated.getSessionId(), evaluated.getTenantId());
                dispatched[0] = true;
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
                dispatched[0] = true;
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
                dispatched[0] = true;
              } else if ("DANGER_DISK_FULL_TERMINATION_REQUIRED".equals(evaluated.getStatusReason())
                  || "DANGER_SECURITY_ISOLATION_TERMINATION_REQUIRED"
                      .equals(evaluated.getStatusReason())) {
                var dangerEvent =
                    evaluated.getStatusReason().startsWith("DANGER_DISK_FULL")
                        ? "DISK_FULL"
                        : "SECURITY_ISOLATION_FAILURE";
                var operation =
                    sessions.terminateForDangerProtection(
                        evaluated.getSessionId(), evaluated.getTenantId(), dangerEvent);
                resources.dangerActionDispatched(
                    evaluated.getSessionId(),
                    dangerEvent,
                    operation.operationId(),
                    "PENDING_NODE_STOP");
                dispatched[0] = true;
              }
            });
    return dispatched[0];
  }
}
