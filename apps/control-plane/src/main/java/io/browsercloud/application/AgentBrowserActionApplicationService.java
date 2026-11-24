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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

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
  private final TransactionOperations transactions;

  @Autowired
  public AgentBrowserActionApplicationService(
      AgentBrowserPerceptionService perception,
      AgentApplicationService tasks,
      AgentExecutionService execution,
      AgentExecutionWorkerApplicationService externalWorker,
      AgentReviewerApplicationService reviewer,
      CoordinatorCommandRoutingService routing,
      PlatformTransactionManager transactionManager) {
    this(
        perception,
        tasks,
        execution,
        externalWorker,
        reviewer,
        routing,
        new TransactionTemplate(transactionManager));
  }

  public AgentBrowserActionApplicationService(
      AgentBrowserPerceptionService perception,
      AgentApplicationService tasks,
      AgentExecutionService execution,
      AgentExecutionWorkerApplicationService externalWorker,
      AgentReviewerApplicationService reviewer,
      CoordinatorCommandRoutingService routing) {
    this(
        perception,
        tasks,
        execution,
        externalWorker,
        reviewer,
        routing,
        TransactionOperations.withoutTransaction());
  }

  AgentBrowserActionApplicationService(
      AgentBrowserPerceptionService perception,
      AgentApplicationService tasks,
      AgentExecutionService execution,
      AgentExecutionWorkerApplicationService externalWorker,
      AgentReviewerApplicationService reviewer,
      CoordinatorCommandRoutingService routing,
      TransactionOperations transactions) {
    this.perception = perception;
    this.tasks = tasks;
    this.execution = execution;
    this.externalWorker = externalWorker;
    this.reviewer = reviewer;
    this.routing = routing;
    this.transactions = transactions;
  }

  public AgentTaskView execute(
      String sessionId, String tenantId, String idempotencyKey, ExecuteActionsRequest request) {
    return act(sessionId, tenantId, idempotencyKey, request);
  }

  public AgentTaskView act(
      String sessionId, String tenantId, String idempotencyKey, ExecuteActionsRequest request) {
    var snapshot = freshSnapshot(sessionId, tenantId, request.expectedStateCursor());
    if (request.navigateUrl() != null) {
      if (request.executionMode() != ExecutionMode.DIRECT || !request.actions().isEmpty()) {
        throw new AgentBrowserActionRejectedException("DIRECT_NAVIGATION_MUST_BE_SEPARATE");
      }
      var create =
          new CreateAgentTaskRequest(
              request.goal(),
              request.navigateUrl(),
              List.of(domain(request.navigateUrl())),
              4,
              0,
              List.of(),
              List.of());
      return createAndExecuteDirect(sessionId, tenantId, idempotencyKey, create);
    }
    if (request.actions().isEmpty()) {
      throw new AgentBrowserActionRejectedException("ACTION_BATCH_SIZE_INVALID");
    }
    if (request.executionMode() == ExecutionMode.DIRECT
        && request.actions().stream()
            .anyMatch(action -> !isDirectActionAllowed(action, snapshot.state()))) {
      throw new AgentBrowserActionRejectedException("DIRECT_EXECUTION_REQUIRES_MANAGED_REVIEW");
    }
    var domains = allowedDomains(snapshot);
    request.actions().stream()
        .filter(action -> action.toolId() == ToolId.OPEN_TAB)
        .map(CreateAgentTaskRequest.BatchActionRequest::tabUrl)
        .map(AgentBrowserActionApplicationService::domain)
        .forEach(domains::add);
    if (domains.isEmpty()) {
      throw new AgentBrowserActionRejectedException("STATE_URL_INVALID");
    }
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
    if (request.executionMode() == ExecutionMode.DIRECT) {
      return createAndExecuteDirect(sessionId, tenantId, idempotencyKey, create);
    }
    return createAndRoute(sessionId, tenantId, idempotencyKey, create);
  }

  public AgentTaskView waitFor(
      String sessionId, String tenantId, String idempotencyKey, WaitRequest request) {
    var snapshot = freshSnapshot(sessionId, tenantId, request.expectedStateCursor());
    var domains = requireAllowedDomains(snapshot);
    var wait =
        new CreateAgentTaskRequest.ActionRequest(
            ToolId.WAIT_FOR,
            request.targetRef(),
            null,
            null,
            null,
            null,
            null,
            request.waitCondition(),
            request.timeoutMs());
    var create =
        new CreateAgentTaskRequest(request.goal(), null, domains, 4, 0, List.of(), List.of(wait));
    return createAndRoute(sessionId, tenantId, idempotencyKey, create);
  }

  public AgentTaskView handoff(
      String sessionId, String tenantId, String idempotencyKey, HandoffRequest request) {
    var snapshot = freshSnapshot(sessionId, tenantId, request.expectedStateCursor());
    var domains = requireAllowedDomains(snapshot);
    var handoff =
        new CreateAgentTaskRequest.ActionRequest(
            ToolId.REQUEST_HUMAN_TAKEOVER, null, null, null, null, null, null, null, null);
    var create =
        new CreateAgentTaskRequest(
            request.goal(), null, domains, 4, 0, List.of(), List.of(handoff));
    return createAndRoute(sessionId, tenantId, idempotencyKey, create);
  }

  private AgentTaskView createAndExecuteDirect(
      String sessionId, String tenantId, String key, CreateAgentTaskRequest create) {
    // Keep creation and deterministic direct-eligibility validation in one transaction so a
    // rejected direct request rolls back cleanly instead of leaving an orphaned PLANNED task.
    var task =
        transactions.execute(
            status -> {
              var created = tasks.createDirect(sessionId, tenantId, create, key + ":create");
              if (created.state() == TaskState.PLANNED) {
                reviewer.requireDirectEligible(created.taskId(), tenantId);
              }
              return created;
            });
    if (task == null || task.state() != TaskState.PLANNED) {
      return task;
    }
    return executeInline(sessionId, tenantId, key + ":execute", task.taskId());
  }

  private AgentTaskView createAndRoute(
      String sessionId, String tenantId, String idempotencyKey, CreateAgentTaskRequest create) {
    var task = tasks.create(sessionId, tenantId, create, idempotencyKey + ":create");
    if (task.state() != TaskState.PLANNED) {
      // SAFE/high-risk policy remains authoritative. The one-call fast path never bypasses a
      // confirmation or retries a blocked plan behind the operator's back.
      return task;
    }
    var executeKey = idempotencyKey + ":execute";
    if (reviewer.enabled()) {
      reviewer.routeForExecution(task.taskId(), tenantId, executeKey);
      return tasks.get(task.taskId(), tenantId);
    }
    if (externalWorker.enabled()) {
      return externalWorker.enqueue(task.taskId(), tenantId, executeKey);
    }
    return executeInline(sessionId, tenantId, executeKey, task.taskId());
  }

  private AgentTaskView executeInline(
      String sessionId, String tenantId, String executeKey, String taskId) {
    return routing.execute(
        sessionId,
        tenantId,
        AGENT_EXECUTE,
        executeKey,
        new AgentExecute(tenantId, taskId, executeKey),
        AgentTaskView.class,
        () -> execution.execute(taskId, tenantId, executeKey));
  }

  private io.browsercloud.api.AgentBrowserPerceptionModels.SnapshotView freshSnapshot(
      String sessionId, String tenantId, String expectedStateCursor) {
    var snapshot = perception.snapshot(sessionId, tenantId);
    if (!snapshot.stateCursor().equals(expectedStateCursor)) {
      throw new AgentBrowserActionRejectedException("STATE_CURSOR_STALE");
    }
    return snapshot;
  }

  private static List<String> requireAllowedDomains(
      io.browsercloud.api.AgentBrowserPerceptionModels.SnapshotView snapshot) {
    var domains = allowedDomains(snapshot);
    if (domains.isEmpty()) {
      throw new AgentBrowserActionRejectedException("STATE_URL_INVALID");
    }
    return List.copyOf(domains);
  }

  private static LinkedHashSet<String> allowedDomains(
      io.browsercloud.api.AgentBrowserPerceptionModels.SnapshotView snapshot) {
    var domains = new LinkedHashSet<String>();
    var current = domainOrNull(snapshot.state().url());
    if (current != null) {
      domains.add(current);
    }
    snapshot.tabs().stream()
        .map(tab -> domainOrNull(tab.url()))
        .filter(java.util.Objects::nonNull)
        .forEach(domains::add);
    return domains;
  }

  private static boolean isDirectActionAllowed(
      CreateAgentTaskRequest.BatchActionRequest action,
      io.browsercloud.api.BrowserStateView state) {
    if (action == null || action.secretId() != null) {
      return false;
    }
    if (AgentReviewerApplicationService.allowsDirectPrimitive(action.toolId())
        || action.toolId() == ToolId.CLOSE_TAB) {
      return action.value() == null;
    }
    if (!AgentReviewerApplicationService.allowsDirectPublicTextPrimitive(action.toolId())) {
      return false;
    }
    if (action.toolId() == ToolId.ACCEPT_DIALOG) {
      return action.value() == null
          || (!action.value().isBlank() && action.value().length() <= 2_000);
    }
    if (action.toolId() == ToolId.CLEAR_TARGET) {
      if (action.value() != null) {
        return false;
      }
    } else if (action.value() == null
        || action.value().isBlank()
        || action.value().length() > 2_000) {
      return false;
    }
    if (action.targetRef() == null || action.targetRef().isBlank()) {
      return false;
    }
    var target =
        state.targets().stream()
            .filter(
                candidate ->
                    action.targetRef().equals(candidate.elementId())
                        || action.targetRef().equals(candidate.targetRef()))
            .findFirst()
            .orElse(null);
    if (target == null
        || !target.enabled()
        || !target.interactive()
        || target.occluded()
        || target.bounds() == null
        || (!target.visible()
            && !(!target.inViewport() && "OUTSIDE_VIEWPORT".equals(target.visibilityReason())))) {
      return false;
    }
    if (action.toolId() == ToolId.SELECT_OPTION) {
      return java.util.Set.of("combobox", "listbox", "button", "generic").contains(target.role());
    }
    return java.util.Set.of("textbox", "combobox").contains(target.role());
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
