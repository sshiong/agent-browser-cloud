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
import io.browsercloud.persistence.AgentTaskEntity;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 通用异步 Agent Step 完成器。
 *
 * <p>保留历史类名以兼容现有 wiring；现在同时处理 Navigate、Click、Type、Scroll、Wait。Node 成功仅表示动作已执行， Control Plane
 * 仍须以新权威状态完成验证。状态验证失败只允许预算内 Full Resync，不会盲目重放写动作。
 */
@Service
public class AgentNavigationCompletionService {

  private final AgentTaskJpaRepository taskRepository;
  private final SessionRepository sessionRepository;
  private final OperationRepository operationRepository;
  private final NodeCommandGateway nodeCommandGateway;
  private final AgentExecutionService executionService;
  private final AgentControlPolicyService controlPolicies;
  private final ObjectMapper objectMapper;

  public AgentNavigationCompletionService(
      AgentTaskJpaRepository taskRepository,
      SessionRepository sessionRepository,
      OperationRepository operationRepository,
      NodeCommandGateway nodeCommandGateway,
      AgentExecutionService executionService,
      AgentControlPolicyService controlPolicies,
      ObjectMapper objectMapper) {
    this.taskRepository = taskRepository;
    this.sessionRepository = sessionRepository;
    this.operationRepository = operationRepository;
    this.nodeCommandGateway = nodeCommandGateway;
    this.executionService = executionService;
    this.controlPolicies = controlPolicies;
    this.objectMapper = objectMapper;
  }

  public void stateUpdated(NodeEventReceived event, NodeEvent.StateUpdated state) {
    stateUpdated(event, state, null);
  }

  public void stateUpdated(
      NodeEventReceived event, NodeEvent.StateUpdated state, String challengeEventId) {
    if (event.operationEpoch() == 0) {
      return;
    }
    var operation = activeOperation(event);
    if (operation.mode() != io.browsercloud.domain.operation.OperationMode.AGENT_INTERACTIVE
        || operation.ownerType() != io.browsercloud.domain.operation.OwnerType.AGENT) {
      return;
    }
    var task = runningTask(operation.actorId(), event.tenantId(), operation.operationId());
    if (task.getPendingStepId() == null) {
      return;
    }
    var plan = readPlan(task.getPlan());
    var step = pendingStep(task, plan);
    var failure = verifyState(task, step, state);
    if (failure != null) {
      replanOrFail(event, task, plan, operation, failure);
      return;
    }
    var verified = verifiedResult(step, state);
    if (challengeEventId != null && !canContinueWithPlannedSensitiveInput(task, plan)) {
      executionService.pauseAfterVerifiedStepForChallenge(
          task.getTaskId(),
          event.tenantId(),
          operation.operationId(),
          step.stepId(),
          verified,
          challengeEventId);
    } else {
      executionService.resumeAfterVerifiedStep(
          task.getTaskId(), event.tenantId(), operation.operationId(), step.stepId(), verified);
    }
  }

  private boolean canContinueWithPlannedSensitiveInput(AgentTaskEntity task, AgentPlan plan) {
    if (!controlPolicies.require(task.getSessionId(), task.getTenantId()).autonomous())
      return false;
    var nextIndex = task.getCurrentStep() + 1;
    if (nextIndex >= plan.steps().size()) return false;
    var next = plan.steps().get(nextIndex);
    return next.toolId() == ToolId.TYPE_TEXT
        && next.input() != null
        && next.input().allowSensitiveTarget()
        && java.util.Set.of(ActionDataClass.CREDENTIAL, ActionDataClass.OTP)
            .contains(next.input().dataClass());
  }

  public void challengeObserved(String sessionId, String tenantId, String challengeEventId) {
    executionService.bindWaitingTaskToChallenge(sessionId, tenantId, challengeEventId);
  }

  public void navigationFailed(
      NodeEventReceived event, NodeEvent.AgentNavigationFailed failureEvent) {
    var operation = activeOperation(event);
    var task = runningTask(failureEvent.taskId(), event.tenantId(), operation.operationId());
    requireMatchingFailure(task, failureEvent.stepId(), ToolId.NAVIGATE.name());
    executionService.failPendingStep(
        task.getTaskId(),
        event.tenantId(),
        operation.operationId(),
        failureEvent.stepId(),
        failureEvent.errorCode());
  }

  public void actionFailed(NodeEventReceived event, NodeEvent.AgentActionFailed failureEvent) {
    var operation = activeOperation(event);
    var task = runningTask(failureEvent.taskId(), event.tenantId(), operation.operationId());
    requireMatchingFailure(task, failureEvent.stepId(), failureEvent.toolId());
    executionService.failPendingStep(
        task.getTaskId(),
        event.tenantId(),
        operation.operationId(),
        failureEvent.stepId(),
        failureEvent.errorCode());
  }

  String verifyState(AgentTaskEntity task, PlanStep step, NodeEvent.StateUpdated state) {
    if (task.getPendingStateVersion() == null
        || state.stateVersion() <= task.getPendingStateVersion()) {
      return "POST_ACTION_STATE_NOT_ADVANCED";
    }
    if (!Set.of("COMPLETE", "DEPTH_LIMITED").contains(state.stateQuality())) {
      return "POST_ACTION_STATE_NOT_EXECUTABLE";
    }
    var stateDomain = stateDomain(state.url());
    if (step.toolId() == ToolId.NAVIGATE
        && !AgentNavigationToolService.domainOf(step.targetUrl()).equals(stateDomain)) {
      return "NAVIGATION_REDIRECT_DOMAIN_MISMATCH";
    }
    if (step.toolId() != ToolId.NAVIGATE
        && (stateDomain == null || !allowedDomains(task).contains(stateDomain))) {
      return "POST_ACTION_DOMAIN_NOT_ALLOWED";
    }
    return null;
  }

  private String stateDomain(String value) {
    try {
      return AgentNavigationToolService.domainOf(value);
    } catch (AgentNavigationToolService.NavigationToolException exception) {
      return null;
    }
  }

  private Set<String> allowedDomains(AgentTaskEntity task) {
    try {
      return Set.of(objectMapper.readValue(task.getAllowedDomains(), String[].class));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to read Agent allowed domains", exception);
    }
  }

  private ToolExecutionResult verifiedResult(PlanStep step, NodeEvent.StateUpdated state) {
    var output = new LinkedHashMap<String, Object>();
    output.put("stateVersion", state.stateVersion());
    output.put("targetRevision", state.targetRevision());
    output.put("stateQuality", state.stateQuality());
    output.put("stateHash", state.stateHash());
    output.put("url", safeUrl(state.url()));
    if (step.toolId() == ToolId.NAVIGATE) {
      output.put("requestedUrl", safeUrl(step.targetUrl()));
      output.put("finalUrl", safeUrl(state.url()));
      output.put("domain", AgentNavigationToolService.domainOf(state.url()));
    }
    if (step.input() != null && step.input().payloadHash() != null) {
      output.put("inputHash", step.input().payloadHash());
      output.put("inputLength", step.input().payloadLength());
      output.put("inputDataClass", step.input().dataClass().name());
    }
    var hash = PromptSecurityService.sha256(write(output));
    return new ToolExecutionResult(
        step.stepId(), step.toolId(), "VERIFIED", hash, output, step.verification(), Instant.now());
  }

  private void replanOrFail(
      NodeEventReceived event,
      AgentTaskEntity task,
      AgentPlan plan,
      ExclusiveOperation operation,
      String failure) {
    if (task.getReplanCount() < plan.replanBudget()
        && plan.expiresAt().isAfter(Instant.now())
        && task.getStepDeadlineAt() != null
        && task.getStepDeadlineAt().isAfter(Instant.now())) {
      task.recordReplan(failure, Instant.now());
      nodeCommandGateway.send(
          NodeCommands.requestAgentStateResync(
              sessionRepository.require(event.sessionId()),
              operation,
              task.getTaskId(),
              "AGENT_STEP_VERIFY_" + failure,
              "agent-replan:" + task.getTaskId() + ":" + task.getReplanCount()));
      taskRepository.save(task);
      return;
    }
    executionService.failPendingStep(
        task.getTaskId(),
        event.tenantId(),
        operation.operationId(),
        task.getPendingStepId(),
        failure + "_REPLAN_BUDGET_EXHAUSTED");
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

  private PlanStep pendingStep(AgentTaskEntity task, AgentPlan plan) {
    if (task.getCurrentStep() >= plan.steps().size()) {
      throw new AgentNavigationCompletionException("PENDING_STEP_MISSING");
    }
    var step = plan.steps().get(task.getCurrentStep());
    if (!step.stepId().equals(task.getPendingStepId())
        || !step.toolId().name().equals(task.getPendingToolId())) {
      throw new AgentNavigationCompletionException("PENDING_STEP_MISMATCH");
    }
    return step;
  }

  private void requireMatchingFailure(AgentTaskEntity task, String stepId, String toolId) {
    if (!stepId.equals(task.getPendingStepId()) || !toolId.equals(task.getPendingToolId())) {
      throw new AgentNavigationCompletionException("PENDING_STEP_MISMATCH");
    }
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
      throw new IllegalStateException("Failed to persist Agent result", exception);
    }
  }

  private static String safeUrl(String value) {
    try {
      var uri = URI.create(value);
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
          .toASCIIString();
    } catch (Exception exception) {
      throw new AgentNavigationCompletionException("STATE_URL_INVALID");
    }
  }

  public static final class AgentNavigationCompletionException extends RuntimeException {
    public AgentNavigationCompletionException(String message) {
      super(message);
    }
  }
}
