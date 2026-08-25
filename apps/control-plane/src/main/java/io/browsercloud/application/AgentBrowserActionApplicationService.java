package io.browsercloud.application;

import static io.browsercloud.api.AgentBrowserActionModels.*;
import static io.browsercloud.application.CoordinatorCommandPayloads.*;

import io.browsercloud.api.AgentTaskView;
import io.browsercloud.api.CreateAgentTaskRequest;
import io.browsercloud.domain.agent.AgentModels.TaskState;
import io.browsercloud.domain.agent.AgentModels.ToolId;
import java.net.IDN;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Coarse Browser Gateway: validates one state cursor, persists one ordered plan, then executes it.
 */
@Service
public class AgentBrowserActionApplicationService {

  private final AgentBrowserPerceptionService perception;
  private final AgentApplicationService tasks;
  private final AgentExecutionService execution;
  private final AgentExecutionWorkerApplicationService externalWorker;
  private final AgentReviewerApplicationService reviewer;
  private final CoordinatorCommandRoutingService routing;

  public AgentBrowserActionApplicationService(
      AgentBrowserPerceptionService perception,
      AgentApplicationService tasks,
      AgentExecutionService execution,
      AgentExecutionWorkerApplicationService externalWorker,
      AgentReviewerApplicationService reviewer,
      CoordinatorCommandRoutingService routing) {
    this.perception = perception;
    this.tasks = tasks;
    this.execution = execution;
    this.externalWorker = externalWorker;
    this.reviewer = reviewer;
    this.routing = routing;
  }

  public AgentTaskView execute(
      String sessionId, String tenantId, String idempotencyKey, ExecuteActionsRequest request) {
    var snapshot = perception.snapshot(sessionId, tenantId);
    if (!snapshot.stateCursor().equals(request.expectedStateCursor())) {
      throw new AgentBrowserActionRejectedException("STATE_CURSOR_STALE");
    }
    var domain = domain(snapshot.state().url());
    var domains = new LinkedHashSet<String>();
    domains.add(domain);
    snapshot.tabs().stream()
        .map(tab -> domainOrNull(tab.url()))
        .filter(java.util.Objects::nonNull)
        .forEach(domains::add);
    request.actions().stream()
        .filter(action -> action.toolId() == ToolId.OPEN_TAB)
        .map(CreateAgentTaskRequest.BatchActionRequest::tabUrl)
        .map(AgentBrowserActionApplicationService::domain)
        .forEach(domains::add);
    var batch =
        new CreateAgentTaskRequest.ActionRequest(
            ToolId.EXECUTE_ACTIONS,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            request.actions(),
            request.stopOnError() == null || request.stopOnError());
    var create =
        new CreateAgentTaskRequest(
            request.goal(),
            null,
            List.copyOf(domains),
            request.actions().size() + 3,
            0,
            List.of(),
            List.of(batch));
    var task = tasks.create(sessionId, tenantId, create, idempotencyKey + ":create");
    if (task.state() != TaskState.PLANNED) {
      // SAFE/high-risk policy remains authoritative. The one-call fast path never bypasses a
      // confirmation or retries a blocked plan behind the operator's back.
      return task;
    }
    var executeKey = idempotencyKey + ":execute";
    if (reviewer.enabled()) {
      reviewer.enqueueForExecution(task.taskId(), tenantId, executeKey);
      return tasks.get(task.taskId(), tenantId);
    }
    if (externalWorker.enabled()) {
      return externalWorker.enqueue(task.taskId(), tenantId, executeKey);
    }
    return routing.execute(
        sessionId,
        tenantId,
        AGENT_EXECUTE,
        executeKey,
        new AgentExecute(tenantId, task.taskId(), executeKey),
        AgentTaskView.class,
        () -> execution.execute(task.taskId(), tenantId, executeKey));
  }

  private static String domain(String url) {
    try {
      var host = URI.create(url).getHost();
      if (host == null) throw new IllegalArgumentException();
      return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException exception) {
      throw new AgentBrowserActionRejectedException("STATE_URL_INVALID");
    }
  }

  private static String domainOrNull(String url) {
    try {
      return domain(url);
    } catch (AgentBrowserActionRejectedException exception) {
      return null;
    }
  }

  public static final class AgentBrowserActionRejectedException extends RuntimeException {
    public AgentBrowserActionRejectedException(String reason) {
      super(reason);
    }
  }
}
