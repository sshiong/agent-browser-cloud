package io.browsercloud.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.browsercloud.coordinator.OperationTimedOut;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.infrastructure.ExclusiveOperationJpaRepository;
import io.browsercloud.persistence.DurableWorkflowEntity;
import io.browsercloud.persistence.DurableWorkflowJpaRepository;
import io.browsercloud.persistence.ExclusiveOperationEntity;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoordinatorDeadlineCommandExecutorTest {

  private final ExclusiveOperationJpaRepository operations =
      mock(ExclusiveOperationJpaRepository.class);
  private final DurableWorkflowJpaRepository workflows = mock(DurableWorkflowJpaRepository.class);
  private final DurableWorkflowApplicationService workflowService =
      mock(DurableWorkflowApplicationService.class);
  private final SessionCoordinator coordinator = mock(SessionCoordinator.class);
  private final StaticProxyApplicationService proxies = mock(StaticProxyApplicationService.class);
  private final SessionResourceAdjustmentLifecycleService resourceAdjustments =
      mock(SessionResourceAdjustmentLifecycleService.class);
  private CoordinatorDeadlineCommandExecutor executor;

  @BeforeEach
  void setUp() {
    executor =
        new CoordinatorDeadlineCommandExecutor(
            operations, workflows, workflowService, coordinator, proxies, resourceAdjustments);
  }

  @Test
  void deadLettersWithoutCallingTheCoordinatorWhenTheOperationIsMissing() {
    var workflow = workflow("NONE");
    when(workflows.findById("wf_test")).thenReturn(Optional.of(workflow));
    when(workflowService.timeout(workflow, "PHASE_DEADLINE_EXCEEDED"))
        .thenReturn(new DurableWorkflowApplicationService.TimeoutDecision(workflow, true));

    executor.workflowTimeout("ses_0000000000000001", "wf_test");

    verify(workflowService).deadLetter(workflow, "COMPENSATION_FAILED");
    verify(coordinator, never()).handle(any(OperationTimedOut.class));
  }

  @Test
  void deadLettersWithoutCompensationAfterTheOperationTimeoutCommits() {
    var workflow = workflow("NONE");
    when(workflows.findById("wf_test")).thenReturn(Optional.of(workflow));
    when(workflowService.timeout(workflow, "PHASE_DEADLINE_EXCEEDED"))
        .thenReturn(new DurableWorkflowApplicationService.TimeoutDecision(workflow, true));
    when(operations.findById("op_test")).thenReturn(Optional.of(activeOperation()));

    executor.workflowTimeout("ses_0000000000000001", "wf_test");

    verify(workflowService).deadLetter(workflow, "NO_SAFE_AUTOMATIC_COMPENSATION");
  }

  @Test
  void releasesNodeCapacityWhenWorkflowTimeoutTerminatesSession() {
    var sessionRepository = mock(io.browsercloud.coordinator.SessionRepository.class);
    var browserCapacity = mock(BrowserCapacityApplicationService.class);
    var capacityAware =
        new CoordinatorDeadlineCommandExecutor(
            operations,
            workflows,
            workflowService,
            coordinator,
            proxies,
            resourceAdjustments,
            sessionRepository,
            browserCapacity);
    var workflow = workflow("RELEASE_PROXY");
    when(workflows.findById("wf_test")).thenReturn(Optional.of(workflow));
    when(workflowService.timeout(workflow, "PHASE_DEADLINE_EXCEEDED"))
        .thenReturn(new DurableWorkflowApplicationService.TimeoutDecision(workflow, true));
    when(operations.findById("op_test")).thenReturn(Optional.of(activeOperation()));
    var terminated =
        new io.browsercloud.domain.session.SessionContext(
            "ses_0000000000000001",
            "tenant-a",
            "profile-1",
            null,
            "rt-1",
            null,
            null,
            1,
            1,
            0,
            0,
            io.browsercloud.domain.session.ResourceClass.L2,
            io.browsercloud.domain.session.SessionState.TERMINATED,
            "",
            java.time.Instant.now(),
            java.time.Instant.now());
    when(sessionRepository.require("ses_0000000000000001")).thenReturn(terminated);

    capacityAware.workflowTimeout("ses_0000000000000001", "wf_test");

    verify(browserCapacity).release("ses_0000000000000001");
    verify(proxies).release("ses_0000000000000001");
    verify(workflowService).markCompensated(workflow, "proxy-released");
  }

  private static DurableWorkflowEntity workflow(String compensationAction) {
    var workflow = new DurableWorkflowEntity();
    workflow.setWorkflowId("wf_test");
    workflow.setSessionId("ses_0000000000000001");
    workflow.setOperationId("op_test");
    workflow.setCompensationAction(compensationAction);
    return workflow;
  }

  private static ExclusiveOperationEntity activeOperation() {
    var operation = new ExclusiveOperationEntity();
    operation.setOperationId("op_test");
    operation.setSessionId("ses_0000000000000001");
    operation.setState("ACTIVE");
    return operation;
  }
}
