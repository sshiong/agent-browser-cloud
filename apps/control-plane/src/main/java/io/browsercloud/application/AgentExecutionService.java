package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.AgentTaskView;
import io.browsercloud.coordinator.OperationFactory;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.ReconcileAgentExecution;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.AgentTaskEntity;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 单 Executor。
 *
 * <p>同步 Tool 每步提交检查点；Node Tool 在 Outbox 入队后持久化 pending step、deadline 与短 lease。任何恢复实例都可从 currentStep
 * 继续，且不会重新消费已完成 Capability。
 */
@Service
public class AgentExecutionService {

  private static final Set<ToolId> ASYNC_ACTIONS =
      Set.of(ToolId.CLICK_TARGET, ToolId.TYPE_TEXT, ToolId.SCROLL, ToolId.WAIT_FOR);

  private final AgentTaskJpaRepository taskRepository;
  private final SessionCoordinator coordinator;
  private final SessionRepository sessionRepository;
  private final OperationRepository operationRepository;
  private final IdempotencyService idempotencyService;
  private final AgentReadToolService readToolService;
  private final AgentNavigationToolService navigationToolService;
  private final AgentActionToolService actionToolService;
  private final AgentHumanGovernanceService governanceService;
  private final AgentApplicationService taskService;
  private final ObjectMapper objectMapper;
  private final long leaseSeconds;
  private final String executorId =
      "cp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

  public AgentExecutionService(
      AgentTaskJpaRepository taskRepository,
      SessionCoordinator coordinator,
      SessionRepository sessionRepository,
      OperationRepository operationRepository,
      IdempotencyService idempotencyService,
      AgentReadToolService readToolService,
      AgentNavigationToolService navigationToolService,
      AgentActionToolService actionToolService,
      AgentHumanGovernanceService governanceService,
      AgentApplicationService taskService,
      ObjectMapper objectMapper,
      @Value("${agent.executor-lease-seconds:30}") long leaseSeconds) {
    this.taskRepository = taskRepository;
    this.coordinator = coordinator;
    this.sessionRepository = sessionRepository;
    this.operationRepository = operationRepository;
    this.idempotencyService = idempotencyService;
    this.readToolService = readToolService;
    this.navigationToolService = navigationToolService;
    this.actionToolService = actionToolService;
    this.governanceService = governanceService;
    this.taskService = taskService;
    this.objectMapper = objectMapper;
    this.leaseSeconds = Math.max(1, Math.min(300, leaseSeconds));
  }

  @Transactional
  public AgentTaskView execute(String taskId, String tenantId, String idempotencyKey) {
    var task = requireTask(taskId, tenantId);
    if (isTerminal(task) || task.getState().equals(TaskState.RUNNING.name())) {
      return taskService.get(taskId, tenantId);
    }
    if (!task.getState().equals(TaskState.PLANNED.name())) {
      throw new AgentExecutionRejectedException("AGENT_TASK_NOT_PLANNED");
    }
    var plan = readPlan(task.getPlan());
    validatePlan(plan);
    var session = requireRunningSession(task, tenantId);
    coordinator.handle(new ReconcileAgentExecution(session.sessionId(), taskId));
    session = requireRunningSession(task, tenantId);

    operationRepository.ensureNoActiveOperation(session.sessionId());
    var operation =
        OperationFactory.agentTask(
            session,
            taskId,
            operationRepository.nextOperationEpoch(session.sessionId()),
            plan.steps().stream()
                .map(step -> step.toolId().name())
                .collect(java.util.stream.Collectors.toSet()));
    var claimedOperationId =
        idempotencyService.claimAgentExecution(
            tenantId, taskId, idempotencyKey, operation.operationId());
    if (!claimedOperationId.equals(operation.operationId())) {
      return taskService.get(taskId, tenantId);
    }
    operationRepository.insert(operation);
    operationRepository.transitionPhase(
        operation.operationId(), OperationPhase.PREPARING, OperationPhase.EXECUTING);
    var now = Instant.now();
    task.startExecution(operation.operationId(), executorId, leaseUntil(now), now);
    taskRepository.save(task);
    drive(task, session, operation, plan, new ArrayList<>());
    return taskService.get(taskId, tenantId);
  }

  /** Node 回调完成当前异步 Step 后继续执行。expectedStepId 使重复或乱序事件保持幂等并 fail-closed。 */
  @Transactional
  public void resumeAfterVerifiedStep(
      String taskId,
      String tenantId,
      String operationId,
      String expectedStepId,
      ToolExecutionResult result) {
    var task = requireTask(taskId, tenantId);
    if (isTerminal(task)) {
      return;
    }
    if (!task.getState().equals(TaskState.RUNNING.name())
        || !operationId.equals(task.getOperationId())
        || !expectedStepId.equals(task.getPendingStepId())) {
      throw new AgentExecutionRejectedException("STALE_AGENT_STEP");
    }
    var operation =
        operationRepository
            .findActive(task.getSessionId())
            .filter(value -> value.operationId().equals(operationId))
            .orElseThrow(() -> new AgentExecutionRejectedException("STALE_AGENT_OPERATION"));
    var session = requireRunningSession(task, tenantId);
    var plan = readPlan(task.getPlan());
    var results = readResults(task.getExecutionResults());
    results.add(result);
    var now = Instant.now();
    task.checkpoint(task.getCurrentStep() + 1, write(results), executorId, leaseUntil(now), now);
    taskRepository.save(task);
    drive(task, session, operation, plan, results);
  }

  @Transactional
  public void failPendingStep(
      String taskId, String tenantId, String operationId, String expectedStepId, String errorCode) {
    var task = requireTask(taskId, tenantId);
    if (isTerminal(task)) {
      return;
    }
    if (!task.getState().equals(TaskState.RUNNING.name())
        || !operationId.equals(task.getOperationId())
        || !expectedStepId.equals(task.getPendingStepId())) {
      throw new AgentExecutionRejectedException("STALE_AGENT_STEP");
    }
    operationRepository
        .findActive(task.getSessionId())
        .filter(value -> value.operationId().equals(operationId))
        .ifPresent(
            operation ->
                operationRepository.transition(
                    operation.operationId(), OperationState.ACTIVE, OperationState.ABORTED));
    task.failExecution(
        task.getCurrentStep(), task.getExecutionResults(), safeCode(errorCode), Instant.now());
    taskRepository.save(task);
  }

  /** 恢复短 lease 过期的 Executor；异步命令不会重放，只等待原 Outbox/Event 直到 deadline。 */
  @Transactional
  public void recover(String taskId, Instant now) {
    var task = taskRepository.findForUpdateByTaskId(taskId).orElse(null);
    if (task == null || !task.getState().equals(TaskState.RUNNING.name())) {
      return;
    }
    coordinator.handle(new ReconcileAgentExecution(task.getSessionId(), task.getTaskId()));
    var currentOperation =
        operationRepository
            .findActive(task.getSessionId())
            .filter(value -> value.operationId().equals(task.getOperationId()));
    if (currentOperation.isEmpty()) {
      task.failExecution(
          task.getCurrentStep(), task.getExecutionResults(), "COORDINATOR_FAILOVER_ABORTED", now);
      taskRepository.save(task);
      return;
    }
    if (task.getStepDeadlineAt() != null && !task.getStepDeadlineAt().isAfter(now)) {
      failPendingStep(
          task.getTaskId(),
          task.getTenantId(),
          task.getOperationId(),
          task.getPendingStepId(),
          "STEP_DEADLINE_EXCEEDED");
      return;
    }
    if (task.getPendingStepId() != null) {
      task.renewLease(executorId, leaseUntil(now), now);
      taskRepository.save(task);
      return;
    }
    var operation = currentOperation.orElseThrow();
    if (operation == null || operation.isExpired(now)) {
      task.failExecution(
          task.getCurrentStep(), task.getExecutionResults(), "AGENT_OPERATION_EXPIRED", now);
      taskRepository.save(task);
      return;
    }
    var session = requireRunningSession(task, task.getTenantId());
    task.renewLease(executorId, leaseUntil(now), now);
    taskRepository.save(task);
    drive(
        task,
        session,
        operation,
        readPlan(task.getPlan()),
        readResults(task.getExecutionResults()));
  }

  private void drive(
      AgentTaskEntity task,
      SessionContext session,
      ExclusiveOperation operation,
      AgentPlan plan,
      ArrayList<ToolExecutionResult> results) {
    try {
      for (int index = task.getCurrentStep(); index < plan.steps().size(); index++) {
        var step = plan.steps().get(index);
        var now = Instant.now();
        if (step.toolId() == ToolId.NAVIGATE) {
          var pending =
              navigationToolService.authorizeAndQueue(
                  task.getTenantId(),
                  session,
                  operation,
                  task.getTaskId(),
                  plan.intentId(),
                  step,
                  now);
          task.markAsyncPending(
              index,
              step.stepId(),
              step.toolId().name(),
              pending.baseStateVersion(),
              pending.baseContentHash(),
              pending.deadline(),
              write(results),
              executorId,
              leaseUntil(now),
              now);
          taskRepository.save(task);
          return;
        }
        if (ASYNC_ACTIONS.contains(step.toolId())) {
          var pending =
              actionToolService.authorizeAndQueue(
                  task.getTenantId(),
                  session,
                  operation,
                  task.getTaskId(),
                  plan.intentId(),
                  step,
                  now);
          task.markAsyncPending(
              index,
              step.stepId(),
              step.toolId().name(),
              pending.baseStateVersion(),
              pending.baseStateHash(),
              pending.deadline(),
              write(results),
              executorId,
              leaseUntil(now),
              now);
          taskRepository.save(task);
          return;
        }
        if (step.toolId() == ToolId.REQUEST_HUMAN_TAKEOVER) {
          var handoff =
              governanceService.requestHandoff(
                  task.getTenantId(),
                  session,
                  operation,
                  task.getTaskId(),
                  plan.intentId(),
                  step,
                  now);
          var output = new java.util.LinkedHashMap<String, Object>();
          output.put("requestId", handoff.requestId());
          output.put("expiresAt", handoff.expiresAt().toString());
          results.add(
              new ToolExecutionResult(
                  step.stepId(),
                  step.toolId(),
                  "WAITING_FOR_HUMAN",
                  PromptSecurityService.sha256(write(output)),
                  output,
                  step.verification(),
                  now));
          operationRepository.transitionPhase(
              operation.operationId(), OperationPhase.EXECUTING, OperationPhase.COMPLETING);
          operationRepository.transition(
              operation.operationId(), OperationState.ACTIVE, OperationState.COMMITTED);
          task.awaitHumanHandoff(
              index + 1, write(results), handoff.requestId(), handoff.expiresAt(), now);
          taskRepository.save(task);
          return;
        }
        results.add(
            readToolService.execute(
                task.getTenantId(), session, task.getTaskId(), plan.intentId(), step, now));
        task.checkpoint(index + 1, write(results), executorId, leaseUntil(now), now);
        taskRepository.save(task);
      }
      operationRepository.transitionPhase(
          operation.operationId(), OperationPhase.EXECUTING, OperationPhase.COMPLETING);
      operationRepository.transition(
          operation.operationId(), OperationState.ACTIVE, OperationState.COMMITTED);
      task.completeExecution(plan.steps().size(), write(results), Instant.now());
      taskRepository.save(task);
    } catch (RuntimeException exception) {
      operationRepository
          .findActive(session.sessionId())
          .filter(active -> active.operationId().equals(operation.operationId()))
          .ifPresent(
              active ->
                  operationRepository.transition(
                      active.operationId(), OperationState.ACTIVE, OperationState.ABORTED));
      task.failExecution(
          task.getCurrentStep(), write(results), safeFailureCode(exception), Instant.now());
      taskRepository.save(task);
    }
  }

  private AgentTaskEntity requireTask(String taskId, String tenantId) {
    return taskRepository
        .findForUpdate(taskId, tenantId)
        .orElseThrow(AgentApplicationService.AgentTaskNotFoundException::new);
  }

  private SessionContext requireRunningSession(AgentTaskEntity task, String tenantId) {
    var session = sessionRepository.require(task.getSessionId());
    if (!session.tenantId().equals(tenantId)) {
      throw new TenantAccessDeniedException(session.sessionId());
    }
    if (session.state() != SessionState.RUNNING) {
      throw new AgentExecutionRejectedException("SESSION_NOT_RUNNING");
    }
    return session;
  }

  private void validatePlan(AgentPlan plan) {
    var now = Instant.now();
    if (!plan.expiresAt().isAfter(now)) {
      throw new AgentExecutionRejectedException("AGENT_PLAN_EXPIRED");
    }
    if (plan.steps().stream().skip(1).anyMatch(step -> step.toolId() == ToolId.NAVIGATE)) {
      throw new AgentExecutionRejectedException("NAVIGATE_MUST_BE_FIRST_STEP");
    }
    if (plan.steps().size() > plan.maxActions()) {
      throw new AgentExecutionRejectedException("PLAN_ACTION_BUDGET_EXCEEDED");
    }
  }

  private AgentPlan readPlan(String value) {
    try {
      return objectMapper.readValue(value, AgentPlan.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to read Agent plan", exception);
    }
  }

  private ArrayList<ToolExecutionResult> readResults(String value) {
    try {
      return new ArrayList<>(
          objectMapper.readValue(value, new TypeReference<List<ToolExecutionResult>>() {}));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to read Agent results", exception);
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to persist Tool results", exception);
    }
  }

  private Instant leaseUntil(Instant now) {
    return now.plusSeconds(leaseSeconds);
  }

  private static boolean isTerminal(AgentTaskEntity task) {
    return task.getState().equals(TaskState.COMPLETED.name())
        || task.getState().equals(TaskState.FAILED.name());
  }

  private static String safeFailureCode(RuntimeException exception) {
    if (exception instanceof AgentReadToolService.ToolExecutionException
        || exception instanceof AgentNavigationToolService.NavigationToolException
        || exception instanceof AgentActionToolService.ActionToolException
        || exception instanceof AgentHumanGovernanceService.HumanGovernanceException
        || exception instanceof AgentCapabilityTokenService.InvalidCapabilityTokenException
        || exception instanceof AgentExecutionRejectedException) {
      return safeCode(exception.getMessage());
    }
    return "TOOL_EXECUTION_FAILED";
  }

  private static String safeCode(String value) {
    if (value != null && value.matches("^[A-Z][A-Z0-9_]{1,127}$")) {
      return value;
    }
    return "TOOL_EXECUTION_FAILED";
  }

  public static final class AgentExecutionRejectedException extends RuntimeException {
    public AgentExecutionRejectedException(String message) {
      super(message);
    }
  }
}
