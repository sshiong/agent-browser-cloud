package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.AgentTaskView;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.RequestHumanTakeover;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.operation.ExclusiveOperation;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.persistence.AgentTaskEntity;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import io.browsercloud.persistence.ToolCapabilityUseJpaRepository;
import java.net.IDN;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 高风险人工确认与 Agent→Human takeover 交接的审计闭环。 */
@Service
public class AgentHumanGovernanceService {

  private final AgentTaskJpaRepository taskRepository;
  private final SessionRepository sessionRepository;
  private final BrowserStateRepository stateRepository;
  private final ToolCapabilityUseJpaRepository capabilityUses;
  private final AgentCapabilityTokenService capabilityTokens;
  private final SessionCoordinator coordinator;
  private final AgentApplicationService taskService;
  private final ObjectMapper objectMapper;

  public AgentHumanGovernanceService(
      AgentTaskJpaRepository taskRepository,
      SessionRepository sessionRepository,
      BrowserStateRepository stateRepository,
      ToolCapabilityUseJpaRepository capabilityUses,
      AgentCapabilityTokenService capabilityTokens,
      SessionCoordinator coordinator,
      AgentApplicationService taskService,
      ObjectMapper objectMapper) {
    this.taskRepository = taskRepository;
    this.sessionRepository = sessionRepository;
    this.stateRepository = stateRepository;
    this.capabilityUses = capabilityUses;
    this.capabilityTokens = capabilityTokens;
    this.coordinator = coordinator;
    this.taskService = taskService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public AgentTaskView approveConfirmation(String taskId, String tenantId, String actorId) {
    var task = requireTask(taskId, tenantId);
    if ("APPROVED".equals(task.getConfirmationStatus())) {
      return taskService.get(taskId, tenantId);
    }
    requirePendingConfirmation(task);
    var now = Instant.now();
    if (!task.getConfirmationExpiresAt().isAfter(now)) {
      task.expireConfirmation(now);
      taskRepository.save(task);
      throw new HumanGovernanceException("HUMAN_CONFIRMATION_EXPIRED");
    }
    task.approveConfirmation(actorId, evidenceHash(task, actorId, "APPROVED"), now);
    taskRepository.save(task);
    return taskService.get(taskId, tenantId);
  }

  @Transactional
  public AgentTaskView rejectConfirmation(String taskId, String tenantId, String actorId) {
    var task = requireTask(taskId, tenantId);
    if ("REJECTED".equals(task.getConfirmationStatus())) {
      return taskService.get(taskId, tenantId);
    }
    requirePendingConfirmation(task);
    var now = Instant.now();
    task.rejectConfirmation(actorId, evidenceHash(task, actorId, "REJECTED"), now);
    taskRepository.save(task);
    return taskService.get(taskId, tenantId);
  }

  public HandoffRequest requestHandoff(
      String tenantId,
      SessionContext session,
      ExclusiveOperation operation,
      String taskId,
      String intentId,
      PlanStep step,
      Instant now) {
    if (step.toolId() != ToolId.REQUEST_HUMAN_TAKEOVER || step.input() != null) {
      throw new HumanGovernanceException("HUMAN_HANDOFF_STEP_INVALID");
    }
    var state =
        stateRepository
            .find(session.sessionId())
            .filter(value -> value.tenantId().equals(tenantId))
            .filter(value -> value.contextEpoch() == session.contextEpoch())
            .map(BrowserStateRepository.Snapshot::state)
            .orElseThrow(() -> new HumanGovernanceException("STATE_CONTEXT_MISMATCH"));
    var claims =
        capabilityTokens.verify(
            step.capabilityToken(),
            tenantId,
            session.sessionId(),
            intentId,
            taskId,
            step.toolId(),
            domainOf(state.url()),
            "HUMAN_HANDOFF",
            now);
    if (capabilityUses.claim(
            claims.tokenId(), tenantId, session.sessionId(), taskId, step.toolId().name(), now)
        != 1) {
      throw new HumanGovernanceException("CAPABILITY_TOKEN_REPLAYED");
    }
    return new HandoffRequest(
        "hof_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        now.plusSeconds(300));
  }

  @Transactional
  public AgentTaskView acceptHandoff(String taskId, String tenantId, String actorId) {
    var task = requireTask(taskId, tenantId);
    if ("ACCEPTED".equals(task.getHandoffStatus())) {
      return taskService.get(taskId, tenantId);
    }
    requirePendingHandoff(task);
    var now = Instant.now();
    if (!task.getHandoffExpiresAt().isAfter(now)) {
      task.expireHumanHandoff(now);
      taskRepository.save(task);
      throw new HumanGovernanceException("HUMAN_HANDOFF_EXPIRED");
    }
    var result = coordinator.handle(new RequestHumanTakeover(task.getSessionId(), actorId));
    var results = readResults(task.getExecutionResults());
    var output = new LinkedHashMap<String, Object>();
    output.put("requestId", task.getHandoffRequestId());
    output.put("takeoverOperationId", result.operationId());
    output.put("actorIdHash", PromptSecurityService.sha256(actorId));
    results.add(
        new ToolExecutionResult(
            "handoff_acceptance",
            ToolId.REQUEST_HUMAN_TAKEOVER,
            "ACCEPTED",
            PromptSecurityService.sha256(write(output)),
            output,
            "HUMAN_TAKEOVER_OPERATION_CREATED",
            now));
    task.acceptHumanHandoff(actorId, write(results), now);
    taskRepository.save(task);
    return taskService.get(taskId, tenantId);
  }

  @Transactional
  public AgentTaskView rejectHandoff(String taskId, String tenantId, String actorId) {
    var task = requireTask(taskId, tenantId);
    if ("REJECTED".equals(task.getHandoffStatus())) {
      return taskService.get(taskId, tenantId);
    }
    requirePendingHandoff(task);
    task.rejectHumanHandoff(actorId, Instant.now());
    taskRepository.save(task);
    return taskService.get(taskId, tenantId);
  }

  @Transactional
  public void expire(String taskId, Instant now) {
    var task = taskRepository.findForUpdateByTaskId(taskId).orElse(null);
    if (task == null) {
      return;
    }
    if ("PENDING".equals(task.getConfirmationStatus())
        && task.getConfirmationExpiresAt() != null
        && !task.getConfirmationExpiresAt().isAfter(now)) {
      task.expireConfirmation(now);
      taskRepository.save(task);
    }
    if ("PENDING".equals(task.getHandoffStatus())
        && task.getHandoffExpiresAt() != null
        && !task.getHandoffExpiresAt().isAfter(now)) {
      task.expireHumanHandoff(now);
      taskRepository.save(task);
    }
  }

  private AgentTaskEntity requireTask(String taskId, String tenantId) {
    var task =
        taskRepository
            .findForUpdate(taskId, tenantId)
            .orElseThrow(AgentApplicationService.AgentTaskNotFoundException::new);
    if (!task.getTenantId().equals(tenantId)) {
      throw new TenantAccessDeniedException(task.getSessionId());
    }
    sessionRepository.require(task.getSessionId());
    return task;
  }

  private static void requirePendingConfirmation(AgentTaskEntity task) {
    if (!task.getState().equals(TaskState.AWAITING_CONFIRMATION.name())
        || !"PENDING".equals(task.getConfirmationStatus())) {
      throw new HumanGovernanceException("HUMAN_CONFIRMATION_NOT_PENDING");
    }
  }

  private static void requirePendingHandoff(AgentTaskEntity task) {
    if (!task.getState().equals(TaskState.WAITING_FOR_HUMAN.name())
        || !"PENDING".equals(task.getHandoffStatus())) {
      throw new HumanGovernanceException("HUMAN_HANDOFF_NOT_PENDING");
    }
  }

  private static String evidenceHash(AgentTaskEntity task, String actorId, String decision) {
    return PromptSecurityService.sha256(
        task.getTaskId()
            + "|"
            + task.getConfirmationId()
            + "|"
            + PromptSecurityService.sha256(task.getPlan())
            + "|"
            + actorId
            + "|"
            + decision);
  }

  private ArrayList<ToolExecutionResult> readResults(String value) {
    try {
      return new ArrayList<>(
          objectMapper.readValue(
              value, new TypeReference<java.util.List<ToolExecutionResult>>() {}));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to read Agent results", exception);
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to persist human governance evidence", exception);
    }
  }

  private static String domainOf(String value) {
    try {
      var host = URI.create(value).getHost();
      if (host == null) {
        throw new HumanGovernanceException("STATE_URL_INVALID");
      }
      return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException exception) {
      throw new HumanGovernanceException("STATE_URL_INVALID");
    }
  }

  public record HandoffRequest(String requestId, Instant expiresAt) {}

  public static final class HumanGovernanceException extends RuntimeException {
    public HumanGovernanceException(String message) {
      super(message);
    }
  }
}
