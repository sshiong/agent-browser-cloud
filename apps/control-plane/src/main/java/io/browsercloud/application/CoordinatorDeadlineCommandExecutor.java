package io.browsercloud.application;

import io.browsercloud.coordinator.OperationTimedOut;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.infrastructure.ExclusiveOperationJpaRepository;
import io.browsercloud.persistence.DurableWorkflowJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Executes deadline commands after they have reached the Session's physical Coordinator shard. */
@Service
public class CoordinatorDeadlineCommandExecutor {

  private final ExclusiveOperationJpaRepository operations;
  private final DurableWorkflowJpaRepository workflows;
  private final DurableWorkflowApplicationService workflowService;
  private final SessionCoordinator coordinator;
  private final StaticProxyApplicationService proxyService;

  public CoordinatorDeadlineCommandExecutor(
      ExclusiveOperationJpaRepository operations,
      DurableWorkflowJpaRepository workflows,
      DurableWorkflowApplicationService workflowService,
      SessionCoordinator coordinator,
      StaticProxyApplicationService proxyService) {
    this.operations = operations;
    this.workflows = workflows;
    this.workflowService = workflowService;
    this.coordinator = coordinator;
    this.proxyService = proxyService;
  }

  @Transactional
  public void operationTimeout(String sessionId, String operationId) {
    var operation = operations.findById(operationId).orElse(null);
    if (operation == null
        || !operation.getSessionId().equals(sessionId)
        || !"ACTIVE".equals(operation.getState())) {
      return;
    }
    coordinator.handle(new OperationTimedOut(sessionId, operationId));
  }

  @Transactional
  public void workflowTimeout(String sessionId, String workflowId) {
    var candidate = workflows.findById(workflowId).orElse(null);
    if (candidate == null || !candidate.getSessionId().equals(sessionId)) {
      return;
    }
    var decision = workflowService.timeout(candidate, "PHASE_DEADLINE_EXCEEDED");
    if (!decision.timedOut()) {
      return;
    }
    var workflow = decision.workflow();
    var operation = operations.findById(workflow.getOperationId()).orElse(null);
    if (operation == null
        || !operation.getSessionId().equals(workflow.getSessionId())
        || !"ACTIVE".equals(operation.getState())) {
      // Avoid entering the transactional Operation repository with a known-stale identity. A
      // thrown @Transactional call would mark the routed command transaction rollback-only and
      // prevent the DLQ evidence from committing.
      workflowService.deadLetter(workflow, "COMPENSATION_FAILED");
      return;
    }
    coordinator.handle(new OperationTimedOut(workflow.getSessionId(), workflow.getOperationId()));
    if ("RELEASE_PROXY".equals(workflow.getCompensationAction())) {
      proxyService.release(workflow.getSessionId());
      workflowService.markCompensated(workflow, "proxy-released");
    } else {
      workflowService.deadLetter(workflow, "NO_SAFE_AUTOMATIC_COMPENSATION");
    }
  }
}
