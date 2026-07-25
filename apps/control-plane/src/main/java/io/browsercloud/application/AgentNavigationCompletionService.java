package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.persistence.AgentTaskEntity;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;

/** 将异步 Node 导航结果提交回 Agent Task，并在预算内执行一次受控 Replan。 */
@Service
public class AgentNavigationCompletionService {

  private final AgentTaskJpaRepository taskRepository;
  private final SessionRepository sessionRepository;
  private final OperationRepository operationRepository;
  private final NodeCommandGateway nodeCommandGateway;
  private final AgentReadToolService readToolService;
  private final ObjectMapper objectMapper;

  public AgentNavigationCompletionService(
      AgentTaskJpaRepository taskRepository,
      SessionRepository sessionRepository,
      OperationRepository operationRepository,
      NodeCommandGateway nodeCommandGateway,
      AgentReadToolService readToolService,
      ObjectMapper objectMapper) {
    this.taskRepository = taskRepository;
    this.sessionRepository = sessionRepository;
    this.operationRepository = operationRepository;
    this.nodeCommandGateway = nodeCommandGateway;
    this.readToolService = readToolService;
    this.objectMapper = objectMapper;
  }

  public void stateUpdated(NodeEventReceived event, NodeEvent.StateUpdated state) {
    if (event.operationEpoch() == 0) {
      return;
    }
    var operation = activeOperation(event);
    var task = runningTask(operation.actorId(), event.tenantId(), operation.operationId());
    var plan = readPlan(task.getPlan());
    var step = navigationStep(task, plan);
    var failure = verifyNavigation(task, step, state);
    if (failure != null) {
      replanOrFail(event, task, plan, operation, failure);
      return;
    }

    var results = new ArrayList<ToolExecutionResult>();
    results.add(navigationResult(step, state));
    try {
      var session = sessionRepository.require(event.sessionId());
      for (int index = task.getCurrentStep() + 1; index < plan.steps().size(); index++) {
        results.add(
            readToolService.execute(
                event.tenantId(),
                session,
                task.getTaskId(),
                plan.intentId(),
                plan.steps().get(index),
                Instant.now()));
      }
      operationRepository.transitionPhase(
          operation.operationId(), OperationPhase.EXECUTING, OperationPhase.COMPLETING);
      operationRepository.transition(
          operation.operationId(), OperationState.ACTIVE, OperationState.COMMITTED);
      task.completeExecution(plan.steps().size(), write(results), Instant.now());
    } catch (RuntimeException exception) {
      abort(task, operation, results, safeFailureCode(exception));
    }
    taskRepository.save(task);
  }

  public void navigationFailed(
      NodeEventReceived event, NodeEvent.AgentNavigationFailed failureEvent) {
    var operation = activeOperation(event);
    var task = runningTask(failureEvent.taskId(), event.tenantId(), operation.operationId());
    var plan = readPlan(task.getPlan());
    var step = navigationStep(task, plan);
    if (!step.stepId().equals(failureEvent.stepId())) {
      abort(task, operation, List.of(), "NAVIGATION_STEP_MISMATCH");
      taskRepository.save(task);
      return;
    }
    if (failureEvent.errorCode().equals("NAVIGATION_STATE_UNAVAILABLE")
        || failureEvent.errorCode().equals("NAVIGATION_STATE_NOT_ADVANCED")) {
      replanOrFail(event, task, plan, operation, failureEvent.errorCode());
      return;
    }
    abort(task, operation, List.of(), failureEvent.errorCode());
    taskRepository.save(task);
  }

  private void replanOrFail(
      NodeEventReceived event,
      AgentTaskEntity task,
      AgentPlan plan,
      ExclusiveOperation operation,
      String failure) {
    if (task.getReplanCount() < plan.replanBudget() && plan.expiresAt().isAfter(Instant.now())) {
      task.recordReplan(Instant.now());
      nodeCommandGateway.send(
          NodeCommands.requestAgentStateResync(
              sessionRepository.require(event.sessionId()),
              operation,
              task.getTaskId(),
              "AGENT_NAVIGATION_VERIFY_" + failure,
              "agent-replan:" + task.getTaskId() + ":" + task.getReplanCount()));
      taskRepository.save(task);
      return;
    }
    abort(task, operation, List.of(), failure + "_REPLAN_BUDGET_EXHAUSTED");
    taskRepository.save(task);
  }

  private ExclusiveOperation activeOperation(NodeEventReceived event) {
    return operationRepository
        .findActive(event.sessionId())
        .filter(operation -> operation.operationEpoch() == event.operationEpoch())
        .orElseThrow(() -> new AgentNavigationCompletionException("STALE_AGENT_OPERATION"));
  }

  private AgentTaskEntity runningTask(String taskId, String tenantId, String operationId) {
    var task =
        taskRepository
            .findForUpdate(taskId, tenantId)
            .orElseThrow(() -> new AgentNavigationCompletionException("AGENT_TASK_NOT_FOUND"));
    if (!task.getState().equals(TaskState.RUNNING.name())
        || !operationId.equals(task.getOperationId())) {
      throw new AgentNavigationCompletionException("STALE_AGENT_TASK");
    }
    return task;
  }

  private PlanStep navigationStep(AgentTaskEntity task, AgentPlan plan) {
    if (task.getCurrentStep() >= plan.steps().size()) {
      throw new AgentNavigationCompletionException("NAVIGATION_STEP_MISSING");
    }
    var step = plan.steps().get(task.getCurrentStep());
    if (step.toolId() != ToolId.NAVIGATE || task.getPendingStateVersion() == null) {
      throw new AgentNavigationCompletionException("NAVIGATION_STEP_MISSING");
    }
    return step;
  }

  private String verifyNavigation(
      AgentTaskEntity task, PlanStep step, NodeEvent.StateUpdated state) {
    if (state.stateVersion() <= task.getPendingStateVersion()) {
      return "NAVIGATION_STATE_NOT_ADVANCED";
    }
    if (!state.stateQuality().equals("COMPLETE") && !state.stateQuality().equals("DEPTH_LIMITED")) {
      return "NAVIGATION_STATE_NOT_EXECUTABLE";
    }
    if (!AgentNavigationToolService.domainOf(step.targetUrl())
        .equals(AgentNavigationToolService.domainOf(state.url()))) {
      return "NAVIGATION_REDIRECT_DOMAIN_MISMATCH";
    }
    return null;
  }

  private ToolExecutionResult navigationResult(PlanStep step, NodeEvent.StateUpdated state) {
    var output = new LinkedHashMap<String, Object>();
    output.put("requestedUrl", safeUrl(step.targetUrl()));
    output.put("finalUrl", safeUrl(state.url()));
    output.put("domain", AgentNavigationToolService.domainOf(state.url()));
    output.put("stateVersion", state.stateVersion());
    output.put("targetRevision", state.targetRevision());
    var hash = PromptSecurityService.sha256(write(output));
    return new ToolExecutionResult(
        step.stepId(), step.toolId(), "VERIFIED", hash, output, step.verification(), Instant.now());
  }

  private void abort(
      AgentTaskEntity task,
      ExclusiveOperation operation,
      List<ToolExecutionResult> results,
      String failure) {
    operationRepository.transition(
        operation.operationId(), OperationState.ACTIVE, OperationState.ABORTED);
    task.failExecution(results.size(), write(results), failure, Instant.now());
  }

  private AgentPlan readPlan(String value) {
    try {
      return objectMapper.readValue(value, AgentPlan.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to read Agent plan", exception);
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to persist Agent navigation result", exception);
    }
  }

  private static String safeUrl(String value) {
    try {
      var uri = URI.create(value);
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
          .toASCIIString();
    } catch (Exception exception) {
      throw new AgentNavigationCompletionException("NAVIGATION_URL_INVALID");
    }
  }

  private static String safeFailureCode(RuntimeException exception) {
    if (exception instanceof AgentReadToolService.ToolExecutionException
        || exception instanceof AgentCapabilityTokenService.InvalidCapabilityTokenException) {
      return exception.getMessage();
    }
    return "TOOL_EXECUTION_FAILED";
  }

  public static final class AgentNavigationCompletionException extends RuntimeException {
    public AgentNavigationCompletionException(String message) {
      super(message);
    }
  }
}
