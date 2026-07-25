package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.AgentTaskView;
import io.browsercloud.coordinator.OperationFactory;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.time.Instant;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Phase 4 单 Executor：当前同步执行并验证只读 Tool Plan。 */
@Service
public class AgentExecutionService {

  private final AgentTaskJpaRepository taskRepository;
  private final SessionRepository sessionRepository;
  private final OperationRepository operationRepository;
  private final IdempotencyService idempotencyService;
  private final AgentReadToolService toolService;
  private final AgentApplicationService taskService;
  private final ObjectMapper objectMapper;

  public AgentExecutionService(
      AgentTaskJpaRepository taskRepository,
      SessionRepository sessionRepository,
      OperationRepository operationRepository,
      IdempotencyService idempotencyService,
      AgentReadToolService toolService,
      AgentApplicationService taskService,
      ObjectMapper objectMapper) {
    this.taskRepository = taskRepository;
    this.sessionRepository = sessionRepository;
    this.operationRepository = operationRepository;
    this.idempotencyService = idempotencyService;
    this.toolService = toolService;
    this.taskService = taskService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public AgentTaskView execute(String taskId, String tenantId, String idempotencyKey) {
    var task =
        taskRepository
            .findForUpdate(taskId, tenantId)
            .orElseThrow(AgentApplicationService.AgentTaskNotFoundException::new);
    if (task.getState().equals(TaskState.COMPLETED.name())
        || task.getState().equals(TaskState.FAILED.name())) {
      return taskService.get(taskId, tenantId);
    }
    if (!task.getState().equals(TaskState.PLANNED.name())) {
      throw new AgentExecutionRejectedException("AGENT_TASK_NOT_PLANNED");
    }
    var plan = readPlan(task.getPlan());
    var now = Instant.now();
    if (!plan.expiresAt().isAfter(now)) {
      throw new AgentExecutionRejectedException("AGENT_PLAN_EXPIRED");
    }
    if (plan.steps().stream().anyMatch(step -> step.toolId() == ToolId.NAVIGATE)) {
      throw new AgentExecutionRejectedException("NAVIGATE_EXECUTOR_NOT_IMPLEMENTED");
    }
    if (plan.steps().size() > plan.maxActions()) {
      throw new AgentExecutionRejectedException("PLAN_ACTION_BUDGET_EXCEEDED");
    }

    var session = sessionRepository.require(task.getSessionId());
    if (!session.tenantId().equals(tenantId)) {
      throw new TenantAccessDeniedException(session.sessionId());
    }
    if (session.state() != SessionState.RUNNING) {
      throw new AgentExecutionRejectedException("SESSION_NOT_RUNNING");
    }

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
    task.startExecution(operation.operationId(), now);
    taskRepository.save(task);

    var results = new ArrayList<ToolExecutionResult>();
    try {
      for (var step : plan.steps()) {
        results.add(
            toolService.execute(tenantId, session, taskId, plan.intentId(), step, Instant.now()));
      }
      operationRepository.transitionPhase(
          operation.operationId(), OperationPhase.EXECUTING, OperationPhase.COMPLETING);
      operationRepository.transition(
          operation.operationId(), OperationState.ACTIVE, OperationState.COMMITTED);
      task.completeExecution(results.size(), write(results), Instant.now());
    } catch (RuntimeException exception) {
      operationRepository.transition(
          operation.operationId(), OperationState.ACTIVE, OperationState.ABORTED);
      task.failExecution(results.size(), write(results), safeFailureCode(exception), Instant.now());
    }
    taskRepository.save(task);
    return taskService.get(taskId, tenantId);
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
      throw new IllegalStateException("Failed to persist Tool results", exception);
    }
  }

  private static String safeFailureCode(RuntimeException exception) {
    if (exception instanceof AgentReadToolService.ToolExecutionException
        || exception instanceof AgentCapabilityTokenService.InvalidCapabilityTokenException) {
      return exception.getMessage();
    }
    return "TOOL_EXECUTION_FAILED";
  }

  public static final class AgentExecutionRejectedException extends RuntimeException {
    public AgentExecutionRejectedException(String message) {
      super(message);
    }
  }
}
