package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.browsercloud.api.AgentBrowserActionModels.ExecuteActionsRequest;
import io.browsercloud.api.AgentBrowserActionModels.ExecutionMode;
import io.browsercloud.api.AgentBrowserActionModels.HandoffRequest;
import io.browsercloud.api.AgentBrowserActionModels.WaitRequest;
import io.browsercloud.api.AgentBrowserPerceptionModels.SnapshotView;
import io.browsercloud.api.AgentTaskView;
import io.browsercloud.api.BrowserStateView;
import io.browsercloud.api.CreateAgentTaskRequest;
import io.browsercloud.domain.agent.AgentModels.TaskState;
import io.browsercloud.domain.agent.AgentModels.ToolId;
import io.browsercloud.domain.agent.AgentModels.WaitCondition;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentBrowserActionApplicationServiceTest {

  @Mock private AgentBrowserPerceptionService perception;
  @Mock private AgentApplicationService tasks;
  @Mock private AgentExecutionService execution;
  @Mock private AgentExecutionWorkerApplicationService externalWorker;
  @Mock private AgentReviewerApplicationService reviewer;
  @Mock private CoordinatorCommandRoutingService routing;

  private AgentBrowserActionApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new AgentBrowserActionApplicationService(
            perception, tasks, execution, externalWorker, reviewer, routing);
    var state =
        new BrowserStateView(
            "ses_1234567890abcdef",
            3,
            9,
            2,
            "https://example.com/login",
            "Login",
            "a".repeat(64),
            "COMPLETE",
            "complete",
            500,
            true,
            List.of(),
            List.of(
                new BrowserStateView.BrowserTabView(
                    "tab-login", "https://example.com/login", "Login", true)),
            "tab-login");
    lenient()
        .when(perception.snapshot("ses_1234567890abcdef", "tenant-test"))
        .thenReturn(new SnapshotView("9:2:" + "a".repeat(64), state));
  }

  @Test
  void createsAndQueuesOneBatchWithoutAddingASecondNavigation() {
    var task = mock(AgentTaskView.class);
    when(task.taskId()).thenReturn("agt_1234567890abcdef");
    when(task.state()).thenReturn(TaskState.PLANNED);
    when(tasks.create(eq("ses_1234567890abcdef"), eq("tenant-test"), any(), eq("idem:create")))
        .thenReturn(task);
    when(reviewer.enabled()).thenReturn(true);
    when(tasks.get("agt_1234567890abcdef", "tenant-test")).thenReturn(task);
    var request = request("9:2:" + "a".repeat(64));

    service.execute("ses_1234567890abcdef", "tenant-test", "idem", request);

    var create = ArgumentCaptor.forClass(CreateAgentTaskRequest.class);
    verify(tasks)
        .create(eq("ses_1234567890abcdef"), eq("tenant-test"), create.capture(), eq("idem:create"));
    org.assertj.core.api.Assertions.assertThat(create.getValue().startUrl()).isNull();
    org.assertj.core.api.Assertions.assertThat(create.getValue().allowedDomains())
        .containsExactly("example.com");
    org.assertj.core.api.Assertions.assertThat(create.getValue().actions().getFirst().toolId())
        .isEqualTo(ToolId.EXECUTE_ACTIONS);
    org.assertj.core.api.Assertions.assertThat(create.getValue().maxActions()).isEqualTo(4);
    verify(reviewer).routeForExecution("agt_1234567890abcdef", "tenant-test", "idem:execute");
  }

  @Test
  void directDispatchRunsOnlyAfterDeterministicGovernanceAndSkipsQueues() {
    var task = mock(AgentTaskView.class);
    when(task.taskId()).thenReturn("agt_1234567890abcdef");
    when(task.state()).thenReturn(TaskState.PLANNED);
    when(tasks.create(anyString(), anyString(), any(), eq("direct:create"))).thenReturn(task);
    var request = request("9:2:" + "a".repeat(64));
    var direct =
        new ExecuteActionsRequest(
            request.goal(),
            request.expectedStateCursor(),
            request.actions(),
            true,
            ExecutionMode.DIRECT);

    service.act("ses_1234567890abcdef", "tenant-test", "direct", direct);

    var ordered = inOrder(reviewer, routing);
    ordered.verify(reviewer).requireDirectEligible("agt_1234567890abcdef", "tenant-test");
    ordered
        .verify(routing)
        .execute(
            eq("ses_1234567890abcdef"),
            eq("tenant-test"),
            anyString(),
            eq("direct:execute"),
            any(),
            eq(AgentTaskView.class),
            any());
    verifyNoMoreInteractions(reviewer);
    verifyNoInteractions(externalWorker);
  }

  @Test
  void directDispatchDoesNotFallbackWhenThePlanRequiresReview() {
    var task = mock(AgentTaskView.class);
    when(task.taskId()).thenReturn("agt_1234567890abcdef");
    when(task.state()).thenReturn(TaskState.PLANNED);
    when(tasks.create(anyString(), anyString(), any(), eq("direct:create"))).thenReturn(task);
    doThrow(
            new AgentBrowserActionApplicationService.AgentBrowserActionRejectedException(
                "DIRECT_EXECUTION_REQUIRES_MANAGED_REVIEW"))
        .when(reviewer)
        .requireDirectEligible("agt_1234567890abcdef", "tenant-test");
    var request = request("9:2:" + "a".repeat(64));

    assertThatThrownBy(
            () ->
                service.act(
                    "ses_1234567890abcdef",
                    "tenant-test",
                    "direct",
                    new ExecuteActionsRequest(
                        request.goal(),
                        request.expectedStateCursor(),
                        request.actions(),
                        true,
                        ExecutionMode.DIRECT)))
        .hasMessage("DIRECT_EXECUTION_REQUIRES_MANAGED_REVIEW");
    verifyNoInteractions(routing, execution, externalWorker);
  }

  @Test
  void directNavigationUsesTheFencedNavigationStepWithoutRequiringTheCurrentHost() {
    var cursor = "9:2:" + "a".repeat(64);
    var snapshot = mock(SnapshotView.class);
    when(snapshot.stateCursor()).thenReturn(cursor);
    when(perception.snapshot("ses_1234567890abcdef", "tenant-test")).thenReturn(snapshot);
    var task = mock(AgentTaskView.class);
    when(task.state()).thenReturn(TaskState.PLANNED);
    when(task.taskId()).thenReturn("agt_1234567890abcdef");
    var create = ArgumentCaptor.forClass(CreateAgentTaskRequest.class);
    when(tasks.create(anyString(), anyString(), create.capture(), eq("navigate:create")))
        .thenReturn(task);

    service.act(
        "ses_1234567890abcdef",
        "tenant-test",
        "navigate",
        new ExecuteActionsRequest(
            "Open the test page",
            cursor,
            List.of(),
            true,
            ExecutionMode.DIRECT,
            "https://example.net/test"));

    org.assertj.core.api.Assertions.assertThat(create.getValue().startUrl())
        .isEqualTo("https://example.net/test");
    org.assertj.core.api.Assertions.assertThat(create.getValue().allowedDomains())
        .containsExactly("example.net");
    org.assertj.core.api.Assertions.assertThat(create.getValue().actions()).isEmpty();
    verify(reviewer).requireDirectEligible("agt_1234567890abcdef", "tenant-test");
  }

  @Test
  void directInputCannotConsumeASecretWhileBuildingThePlan() {
    var action =
        new CreateAgentTaskRequest.BatchActionRequest(
            ToolId.CLICK_TARGET, "e123", 2L, null, "secret-reference", null, null, null, null);
    assertThatThrownBy(
            () ->
                service.act(
                    "ses_1234567890abcdef",
                    "tenant-test",
                    "direct",
                    new ExecuteActionsRequest(
                        "Click a button",
                        "9:2:" + "a".repeat(64),
                        List.of(action),
                        true,
                        ExecutionMode.DIRECT)))
        .hasMessage("DIRECT_EXECUTION_REQUIRES_MANAGED_REVIEW");
    verifyNoInteractions(tasks, reviewer, routing, externalWorker);
  }

  @Test
  void rejectsStaleCursorBeforePersistingOrExecutingAnything() {
    assertThatThrownBy(
            () ->
                service.execute(
                    "ses_1234567890abcdef",
                    "tenant-test",
                    "idem",
                    request("8:2:" + "b".repeat(64))))
        .isInstanceOf(
            AgentBrowserActionApplicationService.AgentBrowserActionRejectedException.class)
        .hasMessage("STATE_CURSOR_STALE");
    verifyNoInteractions(tasks, execution, reviewer, externalWorker, routing);
  }

  @Test
  void includesExplicitOpenTabDomainsWithoutAddingNavigation() {
    var task = mock(AgentTaskView.class);
    when(task.state()).thenReturn(TaskState.AWAITING_CONFIRMATION);
    when(tasks.create(eq("ses_1234567890abcdef"), eq("tenant-test"), any(), eq("idem:create")))
        .thenReturn(task);
    var request =
        new ExecuteActionsRequest(
            "Open support",
            "9:2:" + "a".repeat(64),
            List.of(
                new CreateAgentTaskRequest.BatchActionRequest(
                    ToolId.OPEN_TAB,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "https://support.example.net/ticket")),
            true);

    service.execute("ses_1234567890abcdef", "tenant-test", "idem", request);

    var create = ArgumentCaptor.forClass(CreateAgentTaskRequest.class);
    verify(tasks)
        .create(eq("ses_1234567890abcdef"), eq("tenant-test"), create.capture(), eq("idem:create"));
    org.assertj.core.api.Assertions.assertThat(create.getValue().allowedDomains())
        .containsExactly("example.com", "support.example.net");
    org.assertj.core.api.Assertions.assertThat(
            create.getValue().actions().getFirst().actions().getFirst().tabUrl())
        .isEqualTo("https://support.example.net/ticket");
  }

  @Test
  void fastPathNeverBypassesAnExistingConfirmationDecision() {
    var task = mock(AgentTaskView.class);
    when(task.state()).thenReturn(TaskState.AWAITING_CONFIRMATION);
    when(tasks.create(eq("ses_1234567890abcdef"), eq("tenant-test"), any(), eq("idem:create")))
        .thenReturn(task);

    service.execute(
        "ses_1234567890abcdef", "tenant-test", "idem", request("9:2:" + "a".repeat(64)));

    verifyNoInteractions(execution, reviewer, externalWorker, routing);
  }

  @Test
  void reservesThreeVerificationStepsForTheFullTwentyPrimitiveBatch() {
    var task = mock(AgentTaskView.class);
    when(task.state()).thenReturn(TaskState.AWAITING_CONFIRMATION);
    when(tasks.create(eq("ses_1234567890abcdef"), eq("tenant-test"), any(), eq("idem:create")))
        .thenReturn(task);
    var action =
        new CreateAgentTaskRequest.BatchActionRequest(
            ToolId.MOUSE_MOVE, "e123", 2L, null, null, null, null, null, null);

    service.execute(
        "ses_1234567890abcdef",
        "tenant-test",
        "idem",
        new ExecuteActionsRequest(
            "Bounded full batch",
            "9:2:" + "a".repeat(64),
            java.util.Collections.nCopies(20, action),
            true));

    var create = ArgumentCaptor.forClass(CreateAgentTaskRequest.class);
    verify(tasks)
        .create(eq("ses_1234567890abcdef"), eq("tenant-test"), create.capture(), eq("idem:create"));
    org.assertj.core.api.Assertions.assertThat(create.getValue().maxActions()).isEqualTo(23);
    org.assertj.core.api.Assertions.assertThat(create.getValue().actions().getFirst().actions())
        .hasSize(20);
  }

  @Test
  void exposesActAsTheCanonicalAliasForTheExistingBatchGateway() {
    var task = mock(AgentTaskView.class);
    when(task.state()).thenReturn(TaskState.AWAITING_CONFIRMATION);
    when(tasks.create(eq("ses_1234567890abcdef"), eq("tenant-test"), any(), eq("act:create")))
        .thenReturn(task);

    service.act("ses_1234567890abcdef", "tenant-test", "act", request("9:2:" + "a".repeat(64)));

    verify(tasks).create(eq("ses_1234567890abcdef"), eq("tenant-test"), any(), eq("act:create"));
  }

  @Test
  void createsOneStateFencedWaitTaskWithoutExposingPrimitivePlanFields() {
    var task = mock(AgentTaskView.class);
    when(task.state()).thenReturn(TaskState.AWAITING_CONFIRMATION);
    when(tasks.create(eq("ses_1234567890abcdef"), eq("tenant-test"), any(), eq("wait:create")))
        .thenReturn(task);

    service.waitFor(
        "ses_1234567890abcdef",
        "tenant-test",
        "wait",
        new WaitRequest(
            "Wait for the account menu",
            "9:2:" + "a".repeat(64),
            WaitCondition.TARGET_PRESENT,
            "e-account",
            5_000));

    var create = ArgumentCaptor.forClass(CreateAgentTaskRequest.class);
    verify(tasks)
        .create(eq("ses_1234567890abcdef"), eq("tenant-test"), create.capture(), eq("wait:create"));
    var action = create.getValue().actions().getFirst();
    org.assertj.core.api.Assertions.assertThat(action.toolId()).isEqualTo(ToolId.WAIT_FOR);
    org.assertj.core.api.Assertions.assertThat(action.waitCondition())
        .isEqualTo(WaitCondition.TARGET_PRESENT);
    org.assertj.core.api.Assertions.assertThat(action.targetRef()).isEqualTo("e-account");
    org.assertj.core.api.Assertions.assertThat(action.timeoutMs()).isEqualTo(5_000);
    org.assertj.core.api.Assertions.assertThat(create.getValue().maxActions()).isEqualTo(4);
  }

  @Test
  void createsOneGovernedHumanHandoffAndRoutesItThroughTheReviewer() {
    var task = mock(AgentTaskView.class);
    when(task.taskId()).thenReturn("agt_1234567890abcdef");
    when(task.state()).thenReturn(TaskState.PLANNED);
    when(tasks.create(eq("ses_1234567890abcdef"), eq("tenant-test"), any(), eq("handoff:create")))
        .thenReturn(task);
    when(reviewer.enabled()).thenReturn(true);
    when(tasks.get("agt_1234567890abcdef", "tenant-test")).thenReturn(task);

    service.handoff(
        "ses_1234567890abcdef",
        "tenant-test",
        "handoff",
        new HandoffRequest("Ask an operator to finish this step", "9:2:" + "a".repeat(64)));

    var create = ArgumentCaptor.forClass(CreateAgentTaskRequest.class);
    verify(tasks)
        .create(
            eq("ses_1234567890abcdef"), eq("tenant-test"), create.capture(), eq("handoff:create"));
    org.assertj.core.api.Assertions.assertThat(create.getValue().actions())
        .singleElement()
        .extracting(CreateAgentTaskRequest.ActionRequest::toolId)
        .isEqualTo(ToolId.REQUEST_HUMAN_TAKEOVER);
    verify(reviewer).routeForExecution("agt_1234567890abcdef", "tenant-test", "handoff:execute");
  }

  @Test
  void rejectsStaleHighLevelWaitAndHandoffBeforeCreatingTasks() {
    var stale = "8:2:" + "b".repeat(64);

    assertThatThrownBy(
            () ->
                service.waitFor(
                    "ses_1234567890abcdef",
                    "tenant-test",
                    "wait",
                    new WaitRequest("Wait", stale, WaitCondition.STATE_STABLE, null, 500)))
        .hasMessage("STATE_CURSOR_STALE");
    assertThatThrownBy(
            () ->
                service.handoff(
                    "ses_1234567890abcdef",
                    "tenant-test",
                    "handoff",
                    new HandoffRequest("Handoff", stale)))
        .hasMessage("STATE_CURSOR_STALE");
    verifyNoInteractions(tasks, execution, reviewer, externalWorker, routing);
  }

  private static ExecuteActionsRequest request(String cursor) {
    return new ExecuteActionsRequest(
        "Click the login button",
        cursor,
        List.of(
            new CreateAgentTaskRequest.BatchActionRequest(
                ToolId.CLICK_TARGET, "e123", 2L, null, null, null, null, null, null)),
        true);
  }
}
