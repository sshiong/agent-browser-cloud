package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.browsercloud.api.AgentBrowserActionModels.ExecuteActionsRequest;
import io.browsercloud.api.AgentBrowserPerceptionModels.SnapshotView;
import io.browsercloud.api.AgentTaskView;
import io.browsercloud.api.BrowserStateView;
import io.browsercloud.api.CreateAgentTaskRequest;
import io.browsercloud.domain.agent.AgentModels.TaskState;
import io.browsercloud.domain.agent.AgentModels.ToolId;
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
    verify(reviewer).enqueueForExecution("agt_1234567890abcdef", "tenant-test", "idem:execute");
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
