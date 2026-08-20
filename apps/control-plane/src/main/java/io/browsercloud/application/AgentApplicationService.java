package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.AgentTaskListResponse;
import io.browsercloud.api.AgentTaskView;
import io.browsercloud.api.CreateAgentTaskRequest;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.agent.AgentPolicy;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.infrastructure.OffsetPageRequest;
import io.browsercloud.persistence.AgentTaskEntity;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import java.net.IDN;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 受限 Planner：生成带 Capability、来源、风险和验证规则的可执行计划。 */
@Service
public class AgentApplicationService {

  private final AgentTaskJpaRepository repository;
  private final SessionRepository sessionRepository;
  private final BrowserStateRepository stateRepository;
  private final IdempotencyService idempotencyService;
  private final PromptSecurityService promptSecurityService;
  private final AgentCapabilityTokenService capabilityTokenService;
  private final AgentActionPayloadService actionPayloadService;
  private final AgentControlPolicyService controlPolicyService;
  private final AgentInputSecretApplicationService inputSecrets;
  private final AgentClipboardApplicationService agentClipboard;
  private final AuditApplicationService auditService;
  private final ObjectMapper objectMapper;

  @Autowired
  public AgentApplicationService(
      AgentTaskJpaRepository repository,
      SessionRepository sessionRepository,
      BrowserStateRepository stateRepository,
      IdempotencyService idempotencyService,
      PromptSecurityService promptSecurityService,
      AgentCapabilityTokenService capabilityTokenService,
      AgentActionPayloadService actionPayloadService,
      AgentControlPolicyService controlPolicyService,
      AgentInputSecretApplicationService inputSecrets,
      AgentClipboardApplicationService agentClipboard,
      AuditApplicationService auditService,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.sessionRepository = sessionRepository;
    this.stateRepository = stateRepository;
    this.idempotencyService = idempotencyService;
    this.promptSecurityService = promptSecurityService;
    this.capabilityTokenService = capabilityTokenService;
    this.actionPayloadService = actionPayloadService;
    this.controlPolicyService = controlPolicyService;
    this.inputSecrets = inputSecrets;
    this.agentClipboard = agentClipboard;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
  }

  /** Source compatibility for isolated tests created before AgentClipboard existed. */
  public AgentApplicationService(
      AgentTaskJpaRepository repository,
      SessionRepository sessionRepository,
      BrowserStateRepository stateRepository,
      IdempotencyService idempotencyService,
      PromptSecurityService promptSecurityService,
      AgentCapabilityTokenService capabilityTokenService,
      AgentActionPayloadService actionPayloadService,
      AgentControlPolicyService controlPolicyService,
      AgentInputSecretApplicationService inputSecrets,
      AuditApplicationService auditService,
      ObjectMapper objectMapper) {
    this(
        repository,
        sessionRepository,
        stateRepository,
        idempotencyService,
        promptSecurityService,
        capabilityTokenService,
        actionPayloadService,
        controlPolicyService,
        inputSecrets,
        null,
        auditService,
        objectMapper);
  }

  @Transactional
  public AgentTaskView create(
      String sessionId, String tenantId, CreateAgentTaskRequest request, String idempotencyKey) {
    var descriptor = sessionRepository.describe(sessionId);
    var session = descriptor.context();
    if (!session.tenantId().equals(tenantId)) {
      throw new TenantAccessDeniedException(sessionId);
    }

    var candidateTaskId = newId("agt_");
    var claimedTaskId =
        idempotencyService.claimAgentTask(
            tenantId, sessionId, idempotencyKey, request, candidateTaskId);
    if (!claimedTaskId.equals(candidateTaskId)) {
      return get(claimedTaskId, tenantId);
    }

    var controlPolicy = controlPolicyService.require(sessionId, tenantId);
    var evaluation =
        promptSecurityService.evaluate(
            request.goal(), request.contextSources(), controlPolicy.mode());
    var allowedDomains = normalizeDomains(request.allowedDomains());
    // PostgreSQL stores Instant-backed timestamps at microsecond precision. Normalize before
    // constructing the first response so an idempotent replay loaded from the database is
    // semantically identical on hosts whose clock exposes nanoseconds.
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var intentId = newId("int_");
    var agentPolicy = descriptor.agentPolicy();
    var maxActions =
        request.maxActions() == null ? agentPolicy.defaultMaxActions() : request.maxActions();
    var replanBudget =
        request.replanBudget() == null ? agentPolicy.defaultReplanBudget() : request.replanBudget();
    var expiresAt =
        now.plus(
            evaluation.decision() == IntentDecision.CONFIRM_REQUIRED ? 15 : 5, ChronoUnit.MINUTES);
    var taskRisk =
        maxRisk(
            request.startUrl() == null || request.startUrl().isBlank()
                ? evaluation.riskClass()
                : maxRisk(evaluation.riskClass(), RiskClass.R1_LOW_RISK_CHANGE),
            requestedActionRisk(request.actions()));
    var blockReason =
        validatePlanPreconditions(
            session.state(),
            request.startUrl(),
            allowedDomains,
            evaluation,
            maxActions,
            replanBudget,
            sessionId,
            request.actions(),
            agentPolicy,
            descriptor.humanTakeoverEnabled(),
            controlPolicy);
    var authorizedDomain = resolveAuthorizedDomain(request.startUrl(), sessionId);
    var plan =
        blockReason.isBlank()
            ? buildPlan(
                tenantId,
                sessionId,
                candidateTaskId,
                intentId,
                request.startUrl(),
                authorizedDomain,
                request.actions(),
                maxActions,
                replanBudget,
                expiresAt,
                controlPolicy)
            : new AgentPlan(intentId, List.of(), maxActions, replanBudget, expiresAt);
    var securityEvents = new ArrayList<>(evaluation.securityEvents());
    if (!blockReason.isBlank()) {
      securityEvents.add(
          new SecurityEvent(
              newId("sec_"),
              "PLAN_VALIDATION",
              "HIGH",
              "BLOCK",
              blockReason,
              InstructionSourceType.PLATFORM_POLICY,
              PromptSecurityService.sha256(evaluation.sanitizedGoal()),
              now));
    }
    var state =
        !blockReason.isBlank()
            ? TaskState.BLOCKED
            : evaluation.decision() == IntentDecision.CONFIRM_REQUIRED
                ? TaskState.AWAITING_CONFIRMATION
                : TaskState.PLANNED;
    var entity =
        new AgentTaskEntity(
            candidateTaskId,
            tenantId,
            sessionId,
            evaluation.sanitizedGoal(),
            state.name(),
            taskRisk.name(),
            evaluation.decision().name(),
            blockReason.isBlank() ? null : blockReason,
            agentPolicy,
            write(allowedDomains),
            write(plan),
            write(securityEvents),
            now);
    if (state == TaskState.AWAITING_CONFIRMATION) {
      entity.awaitConfirmation(newId("cnf_"), now.plus(5, ChronoUnit.MINUTES), now);
    }
    repository.save(entity);
    appendSecurityAudit(tenantId, sessionId, candidateTaskId, securityEvents);
    return toView(entity);
  }

  private void appendSecurityAudit(
      String tenantId, String sessionId, String taskId, List<SecurityEvent> events) {
    for (var event : events) {
      auditService.append(
          new AuditApplicationService.AuditRecord(
              tenantId,
              sessionId,
              "SECURITY_EVENT",
              "SYSTEM",
              "agent-safety-kernel",
              "AGENT_TASK",
              taskId,
              event.eventType(),
              event.decision(),
              Map.of(
                  "severity",
                  event.severity(),
                  "ruleCode",
                  event.ruleCode(),
                  "sourceType",
                  event.sourceType().name(),
                  "contentHash",
                  event.contentHash()),
              event.eventId()));
    }
  }

  @Transactional(readOnly = true)
  public AgentTaskView get(String taskId, String tenantId) {
    return repository
        .findByTaskIdAndTenantId(taskId, tenantId)
        .map(this::toView)
        .orElseThrow(AgentTaskNotFoundException::new);
  }

  @Transactional(readOnly = true)
  public AgentTaskListResponse list(String tenantId, int limit, int offset) {
    var safeLimit = Math.max(1, Math.min(limit, 100));
    var safeOffset = Math.max(0, offset);
    var page =
        new OffsetPageRequest(safeOffset, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt"));
    var items =
        repository.findAllByTenantIdOrderByCreatedAtDesc(tenantId, page).stream()
            .map(this::toView)
            .toList();
    return new AgentTaskListResponse(
        items,
        Math.toIntExact(Math.min(repository.countByTenantId(tenantId), Integer.MAX_VALUE)),
        safeLimit,
        safeOffset);
  }

  private String validatePlanPreconditions(
      SessionState sessionState,
      String startUrl,
      List<String> allowedDomains,
      IntentEvaluation evaluation,
      int maxActions,
      int replanBudget,
      String sessionId,
      List<CreateAgentTaskRequest.ActionRequest> requestedActions,
      AgentPolicy agentPolicy,
      boolean humanTakeoverEnabled,
      AgentControlPolicyService.Policy controlPolicy) {
    var actions =
        requestedActions == null
            ? List.<CreateAgentTaskRequest.ActionRequest>of()
            : requestedActions;
    var policyViolation =
        validateAgentPolicy(
            agentPolicy, humanTakeoverEnabled, startUrl, actions, maxActions, replanBudget);
    if (!policyViolation.isBlank()) {
      return policyViolation;
    }
    if (evaluation.decision() == IntentDecision.FORBIDDEN) {
      return evaluation.reason();
    }
    if (sessionState != SessionState.RUNNING) {
      return "SESSION_NOT_RUNNING";
    }
    var targetDomain = domainOf(startUrl);
    if (startUrl != null && !startUrl.isBlank() && targetDomain == null) {
      return "NAVIGATION_URL_INVALID";
    }
    if (targetDomain != null && !allowedDomains.contains(targetDomain)) {
      return "DOMAIN_NOT_ALLOWED";
    }
    if (targetDomain != null && !actions.isEmpty()) {
      return "TARGET_ACTION_AFTER_NAVIGATION_REQUIRES_REPLAN";
    }
    var endsWithHandoff =
        !actions.isEmpty() && actions.getLast().toolId() == ToolId.REQUEST_HUMAN_TAKEOVER;
    var requestedActionCount =
        actions.stream()
            .mapToInt(
                action -> action.toolId() == ToolId.EXECUTE_ACTIONS ? action.actions().size() : 1)
            .sum();
    var neededActions =
        (targetDomain == null ? 3 : 4) + requestedActionCount - (endsWithHandoff ? 2 : 0);
    if (maxActions < neededActions) {
      return "MAX_ACTIONS_TOO_SMALL";
    }
    var snapshot = stateRepository.find(sessionId);
    if (snapshot.isPresent()) {
      var quality = snapshot.orElseThrow().state().stateQuality();
      if (!quality.equals("COMPLETE") && !quality.equals("DEPTH_LIMITED")) {
        return "STATE_QUALITY_NOT_EXECUTABLE";
      }
      if (targetDomain == null) {
        var currentDomain = domainOf(snapshot.orElseThrow().state().url());
        if (currentDomain == null || !allowedDomains.contains(currentDomain)) {
          return "CURRENT_DOMAIN_NOT_ALLOWED";
        }
      }
      var actionError =
          validateRequestedActions(actions, snapshot.orElseThrow().state(), controlPolicy);
      if (!actionError.isBlank()) {
        return actionError;
      }
    } else if (targetDomain == null) {
      return "STATE_UNAVAILABLE";
    } else if (!actions.isEmpty()) {
      return "STATE_UNAVAILABLE";
    }
    return "";
  }

  private String validateRequestedActions(
      List<CreateAgentTaskRequest.ActionRequest> actions,
      io.browsercloud.coordinator.NodeEvent.StateUpdated state,
      AgentControlPolicyService.Policy controlPolicy) {
    var handoffCount =
        actions.stream().filter(action -> action.toolId() == ToolId.REQUEST_HUMAN_TAKEOVER).count();
    if (handoffCount > 1
        || (handoffCount == 1 && actions.getLast().toolId() != ToolId.REQUEST_HUMAN_TAKEOVER)) {
      return "HUMAN_HANDOFF_MUST_BE_FINAL";
    }
    for (var action : actions) {
      switch (action.toolId()) {
        case CLICK_TARGET, TYPE_TEXT, FILL, PASTE_AGENT_CLIPBOARD -> {
          if (action.targetRef() == null
              || action.targetRef().isBlank()
              || action.targetRevision() == null
              || action.targetRevision() != state.targetRevision()) {
            return "TARGET_BINDING_INVALID";
          }
          var target =
              state.targets().stream()
                  .filter(
                      candidate ->
                          candidate.targetRef().equals(action.targetRef())
                              || action.targetRef().equals(candidate.elementId()))
                  .findFirst()
                  .orElse(null);
          if (target == null || !target.visible() || !target.enabled() || target.bounds() == null) {
            return "TARGET_NOT_ACTIONABLE";
          }
          if (isTextInput(action.toolId())) {
            var sensitiveData =
                action.dataClass() == ActionDataClass.CREDENTIAL
                    || action.dataClass() == ActionDataClass.OTP;
            if (isSecretInput(action.toolId())
                && action.value() != null
                && action.secretId() != null) {
              return "TYPE_TEXT_VALUE_SOURCE_AMBIGUOUS";
            }
            if (isSecretInput(action.toolId())
                && action.secretId() != null
                && (!controlPolicy.autonomous() || !sensitiveData)) {
              return "AUTONOMOUS_SENSITIVE_INPUT_REQUIRED";
            }
            if (target.sensitive()
                && (!controlPolicy.autonomous() || action.secretId() == null || !sensitiveData)) {
              return "SENSITIVE_TARGET_FORBIDDEN";
            }
            if (!java.util.Set.of("textbox", "combobox").contains(target.role())) {
              return "TYPE_TARGET_ROLE_INVALID";
            }
            if (isSecretInput(action.toolId())
                && (action.value() == null || action.value().isBlank())
                && action.secretId() == null) {
              return "TYPE_TEXT_VALUE_REQUIRED";
            }
            if (isSecretInput(action.toolId())
                && action.value() != null
                && AgentDataMinimizer.containsCredentialLikeValue(action.value())) {
              return "CREDENTIAL_VALUE_FORBIDDEN";
            }
            if (action.toolId() == ToolId.PASTE_AGENT_CLIPBOARD
                && (action.value() != null
                    || action.secretId() != null
                    || action.dataClass() != null)) {
              return "AGENT_CLIPBOARD_PASTE_SOURCE_FORBIDDEN";
            }
          }
        }
        case SCROLL -> {
          if (action.scrollDeltaY() == null
              || Math.abs(action.scrollDeltaY()) < 100
              || Math.abs(action.scrollDeltaY()) > 2_000) {
            return "SCROLL_DELTA_INVALID";
          }
        }
        case WAIT_FOR -> {
          if (action.waitCondition() == null
              || action.timeoutMs() == null
              || action.timeoutMs() < 100
              || action.timeoutMs() > 10_000) {
            return "WAIT_CONDITION_INVALID";
          }
          if (action.waitCondition() == WaitCondition.TARGET_PRESENT
              && (action.targetRef() == null || action.targetRef().isBlank())) {
            return "WAIT_TARGET_REQUIRED";
          }
        }
        case EXECUTE_ACTIONS -> {
          if (action.targetRef() != null
              || action.targetRevision() != null
              || action.value() != null
              || action.secretId() != null
              || action.dataClass() != null
              || action.scrollDeltaY() != null
              || action.waitCondition() != null
              || action.timeoutMs() != null
              || action.actions().isEmpty()) {
            return "BATCH_ACTION_INPUT_INVALID";
          }
          var primitives =
              action.actions().stream()
                  .map(
                      primitive ->
                          new CreateAgentTaskRequest.ActionRequest(
                              primitive.toolId(),
                              primitive.targetRef(),
                              primitive.targetRevision(),
                              primitive.value(),
                              primitive.secretId(),
                              primitive.dataClass(),
                              primitive.scrollDeltaY(),
                              primitive.waitCondition(),
                              primitive.timeoutMs()))
                  .toList();
          if (primitives.stream()
              .anyMatch(
                  primitive ->
                      !java.util.Set.of(
                              ToolId.CLICK_TARGET,
                              ToolId.TYPE_TEXT,
                              ToolId.FILL,
                              ToolId.PASTE_AGENT_CLIPBOARD,
                              ToolId.SCROLL,
                              ToolId.WAIT_FOR)
                          .contains(primitive.toolId()))) {
            return "BATCH_ACTION_TOOL_FORBIDDEN";
          }
          var primitiveError = validateRequestedActions(primitives, state, controlPolicy);
          if (!primitiveError.isBlank()) {
            return primitiveError;
          }
        }
        case REQUEST_HUMAN_TAKEOVER -> {
          if (action.targetRef() != null
              || action.targetRevision() != null
              || action.value() != null
              || action.secretId() != null
              || action.dataClass() != null
              || action.scrollDeltaY() != null
              || action.waitCondition() != null
              || action.timeoutMs() != null) {
            return "HUMAN_HANDOFF_INPUT_FORBIDDEN";
          }
        }
        default -> {
          return "REQUESTED_TOOL_NOT_SUPPORTED";
        }
      }
    }
    return "";
  }

  private AgentPlan buildPlan(
      String tenantId,
      String sessionId,
      String operationId,
      String intentId,
      String startUrl,
      String authorizedDomain,
      List<CreateAgentTaskRequest.ActionRequest> requestedActions,
      int maxActions,
      int replanBudget,
      Instant expiresAt,
      AgentControlPolicyService.Policy controlPolicy) {
    var steps = new ArrayList<PlanStep>();
    var actions =
        requestedActions == null
            ? List.<CreateAgentTaskRequest.ActionRequest>of()
            : requestedActions;
    var targetDomain = domainOf(startUrl);
    if (targetDomain != null) {
      steps.add(
          step(
              tenantId,
              sessionId,
              operationId,
              intentId,
              ToolId.NAVIGATE,
              RiskClass.R1_LOW_RISK_CHANGE,
              startUrl,
              null,
              "Open the user-authorized starting URL",
              targetDomain,
              "SESSION_RUNNING",
              "URL_HOST_EQUALS_ALLOWED_DOMAIN",
              expiresAt));
    }
    steps.add(
        step(
            tenantId,
            sessionId,
            operationId,
            intentId,
            ToolId.GET_CURRENT_STATE,
            RiskClass.R0_READ_ONLY,
            null,
            null,
            "Read stable browser state before any semantic action",
            authorizedDomain,
            targetDomain == null
                ? "COMPLETE_OR_DEPTH_LIMITED"
                : "COMPLETE_OR_DEPTH_LIMITED_AFTER_NAVIGATION",
            "STATE_VERSION_PRESENT",
            expiresAt));
    for (var action : actions) {
      steps.add(
          actionStep(
              tenantId,
              sessionId,
              operationId,
              intentId,
              authorizedDomain,
              action,
              expiresAt,
              controlPolicy));
    }
    var endsWithHandoff =
        !actions.isEmpty() && actions.getLast().toolId() == ToolId.REQUEST_HUMAN_TAKEOVER;
    if (!endsWithHandoff) {
      steps.add(
          step(
              tenantId,
              sessionId,
              operationId,
              intentId,
              ToolId.GET_URL,
              RiskClass.R0_READ_ONLY,
              null,
              null,
              "Verify the browser remains inside the authorized domain",
              authorizedDomain,
              "COMPLETE_OR_DEPTH_LIMITED",
              "URL_HOST_EQUALS_ALLOWED_DOMAIN",
              expiresAt));
      steps.add(
          step(
              tenantId,
              sessionId,
              operationId,
              intentId,
              ToolId.GET_PAGE_SUMMARY,
              RiskClass.R0_READ_ONLY,
              null,
              null,
              "Return a bounded data-only page summary",
              authorizedDomain,
              "COMPLETE_OR_DEPTH_LIMITED",
              "SUMMARY_SCHEMA_VALID",
              expiresAt));
    }
    return new AgentPlan(intentId, List.copyOf(steps), maxActions, replanBudget, expiresAt);
  }

  private PlanStep step(
      String tenantId,
      String sessionId,
      String operationId,
      String intentId,
      ToolId toolId,
      RiskClass riskClass,
      String targetUrl,
      StepInput input,
      String rationale,
      String allowedDomain,
      String requiredStateQuality,
      String verification,
      Instant expiresAt) {
    var capability =
        capabilityTokenService.issue(
            tenantId,
            sessionId,
            intentId,
            operationId,
            toolId,
            allowedDomain,
            dataScope(toolId, input),
            riskClass,
            expiresAt);
    return new PlanStep(
        newId("step_"),
        toolId,
        riskClass,
        targetUrl,
        input,
        rationale,
        List.of("user_goal", "platform_policy"),
        TrustLevel.TRUSTED,
        List.of(),
        false,
        ExecutionStrategy.SEMANTIC_DOM,
        requiredStateQuality,
        verification,
        capability.tokenId(),
        capability.token());
  }

  private PlanStep actionStep(
      String tenantId,
      String sessionId,
      String taskId,
      String intentId,
      String allowedDomain,
      CreateAgentTaskRequest.ActionRequest request,
      Instant expiresAt,
      AgentControlPolicyService.Policy controlPolicy) {
    var stepId = newId("step_");
    var input =
        request.toolId() == ToolId.EXECUTE_ACTIONS
            ? batchInput(tenantId, sessionId, taskId, stepId, request, controlPolicy)
            : singleActionInput(tenantId, sessionId, taskId, stepId, request, controlPolicy);
    var risk =
        switch (request.toolId()) {
          case TYPE_TEXT, FILL, PASTE_AGENT_CLIPBOARD -> RiskClass.R2_DATA_CHANGE;
          case CLICK_TARGET, SCROLL -> RiskClass.R1_LOW_RISK_CHANGE;
          case WAIT_FOR, REQUEST_HUMAN_TAKEOVER -> RiskClass.R0_READ_ONLY;
          case EXECUTE_ACTIONS ->
              request.actions().stream()
                  .map(
                      action ->
                          isTextInput(action.toolId())
                              ? RiskClass.R2_DATA_CHANGE
                              : action.toolId() == ToolId.WAIT_FOR
                                  ? RiskClass.R0_READ_ONLY
                                  : RiskClass.R1_LOW_RISK_CHANGE)
                  .reduce(RiskClass.R0_READ_ONLY, AgentApplicationService::maxRisk);
          default -> RiskClass.R5_SECURITY;
        };
    var capability =
        capabilityTokenService.issue(
            tenantId,
            sessionId,
            intentId,
            taskId,
            request.toolId(),
            allowedDomain,
            dataScope(request.toolId(), input),
            risk,
            expiresAt);
    return new PlanStep(
        stepId,
        request.toolId(),
        risk,
        null,
        input,
        rationale(request.toolId()),
        List.of("user_goal", "platform_policy"),
        TrustLevel.TRUSTED,
        List.of(),
        false,
        request.toolId() == ToolId.WAIT_FOR
            ? ExecutionStrategy.SEMANTIC_DOM
            : request.toolId() == ToolId.REQUEST_HUMAN_TAKEOVER
                ? ExecutionStrategy.HUMAN_ASSIST
                : ExecutionStrategy.DESKTOP_INPUT,
        "COMPLETE_OR_DEPTH_LIMITED_AND_TARGET_REVISION_MATCH",
        verification(request.toolId()),
        capability.tokenId(),
        capability.token());
  }

  private StepInput singleActionInput(
      String tenantId,
      String sessionId,
      String taskId,
      String stepId,
      CreateAgentTaskRequest.ActionRequest request,
      AgentControlPolicyService.Policy controlPolicy) {
    if (request.toolId() == ToolId.REQUEST_HUMAN_TAKEOVER) return null;
    var dataClass = request.dataClass() == null ? ActionDataClass.PUBLIC : request.dataClass();
    var resolvedSecret =
        request.secretId() == null
            ? null
            : inputSecrets.consume(request.secretId(), sessionId, tenantId, taskId, dataClass);
    var plaintext =
        request.toolId() == ToolId.PASTE_AGENT_CLIPBOARD
            ? requireClipboard().materializeForPaste(sessionId, tenantId)
            : resolvedSecret == null ? request.value() : resolvedSecret.plaintext();
    var sensitiveInput =
        dataClass == ActionDataClass.CREDENTIAL || dataClass == ActionDataClass.OTP;
    var sealedPayload =
        isTextInput(request.toolId())
            ? actionPayloadService.seal(tenantId, taskId, stepId, plaintext)
            : null;
    return new StepInput(
        request.targetRef(),
        request.targetRevision(),
        sealedPayload,
        isTextInput(request.toolId())
            ? PromptSecurityService.sha256(sensitiveInput ? sealedPayload : plaintext)
            : null,
        isTextInput(request.toolId()) ? plaintext.length() : null,
        isTextInput(request.toolId()) ? dataClass : null,
        request.scrollDeltaY(),
        request.waitCondition(),
        request.timeoutMs(),
        isSecretInput(request.toolId()) && sensitiveInput && controlPolicy.autonomous(),
        isSecretInput(request.toolId()) && sensitiveInput
            ? controlPolicy.sensitiveInputMaximumAttempts()
            : 1);
  }

  private StepInput batchInput(
      String tenantId,
      String sessionId,
      String taskId,
      String stepId,
      CreateAgentTaskRequest.ActionRequest request,
      AgentControlPolicyService.Policy controlPolicy) {
    var actions = new ArrayList<ActionInput>();
    for (int index = 0; index < request.actions().size(); index++) {
      var action = request.actions().get(index);
      var actionId = "action_" + (index + 1);
      var dataClass = action.dataClass() == null ? ActionDataClass.PUBLIC : action.dataClass();
      var secret =
          action.secretId() == null
              ? null
              : inputSecrets.consume(action.secretId(), sessionId, tenantId, taskId, dataClass);
      var plaintext =
          action.toolId() == ToolId.PASTE_AGENT_CLIPBOARD
              ? requireClipboard().materializeForPaste(sessionId, tenantId)
              : secret == null ? action.value() : secret.plaintext();
      var sensitive = dataClass == ActionDataClass.CREDENTIAL || dataClass == ActionDataClass.OTP;
      var sealed =
          isTextInput(action.toolId())
              ? actionPayloadService.seal(tenantId, taskId, stepId + ":" + actionId, plaintext)
              : null;
      actions.add(
          new ActionInput(
              actionId,
              action.toolId(),
              action.targetRef(),
              action.targetRevision(),
              sealed,
              isTextInput(action.toolId())
                  ? PromptSecurityService.sha256(sensitive ? sealed : plaintext)
                  : null,
              isTextInput(action.toolId()) ? plaintext.length() : null,
              isTextInput(action.toolId()) ? dataClass : null,
              action.scrollDeltaY(),
              action.waitCondition(),
              action.timeoutMs(),
              isSecretInput(action.toolId()) && sensitive && controlPolicy.autonomous(),
              isSecretInput(action.toolId()) && sensitive
                  ? controlPolicy.sensitiveInputMaximumAttempts()
                  : 1));
    }
    return new StepInput(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        1,
        actions,
        request.stopOnError() == null || request.stopOnError());
  }

  private static String dataScope(ToolId toolId, StepInput input) {
    return switch (toolId) {
      case NAVIGATE -> "NAVIGATION";
      case CLICK_TARGET -> "TARGET_ACTION";
      case TYPE_TEXT, FILL, PASTE_AGENT_CLIPBOARD ->
          input == null
              ? "FORM_INPUT_PUBLIC"
              : switch (input.dataClass()) {
                case PII -> "FORM_INPUT_PII";
                case CREDENTIAL -> "FORM_INPUT_CREDENTIAL";
                case OTP -> "FORM_INPUT_OTP";
                default -> "FORM_INPUT_PUBLIC";
              };
      case SCROLL -> "VIEWPORT_ACTION";
      case WAIT_FOR -> "STATE_OBSERVATION";
      case EXECUTE_ACTIONS -> "BATCH_ACTIONS";
      case REQUEST_HUMAN_TAKEOVER -> "HUMAN_HANDOFF";
      default -> "BROWSER_STATE_METADATA";
    };
  }

  private static String rationale(ToolId toolId) {
    return switch (toolId) {
      case CLICK_TARGET -> "Click the exact user-authorized current-state target";
      case TYPE_TEXT -> "Type sealed purpose-bound text into the exact authorized target";
      case FILL -> "Replace the exact authorized field with sealed purpose-bound text";
      case PASTE_AGENT_CLIPBOARD ->
          "Paste the isolated AgentClipboard into the exact authorized target";
      case SCROLL -> "Scroll the current page by a bounded amount";
      case WAIT_FOR -> "Wait for a bounded browser-state condition";
      case EXECUTE_ACTIONS -> "Execute an ordered, state-fenced browser action batch";
      case REQUEST_HUMAN_TAKEOVER -> "Pause the Agent and request an explicit human takeover";
      default -> "Execute the authorized action";
    };
  }

  private static String verification(ToolId toolId) {
    return switch (toolId) {
      case CLICK_TARGET -> "POST_ACTION_STATE_VERSION_ADVANCED";
      case TYPE_TEXT, FILL, PASTE_AGENT_CLIPBOARD ->
          "POST_ACTION_STATE_ADVANCED_AND_PAYLOAD_HASH_BOUND";
      case SCROLL -> "POST_SCROLL_STATE_COLLECTED";
      case WAIT_FOR -> "WAIT_CONDITION_SATISFIED";
      case EXECUTE_ACTIONS -> "BATCH_ACTIONS_VERIFIED_WITH_FINAL_STATE";
      case REQUEST_HUMAN_TAKEOVER -> "HUMAN_HANDOFF_REQUEST_RECORDED";
      default -> "RESULT_VERIFIED";
    };
  }

  private AgentTaskView toView(AgentTaskEntity entity) {
    var plan = read(entity.getPlan(), AgentPlan.class);
    var events = read(entity.getSecurityEvents(), new TypeReference<List<SecurityEvent>>() {});
    var executionResults =
        read(entity.getExecutionResults(), new TypeReference<List<ToolExecutionResult>>() {});
    var domains = read(entity.getAllowedDomains(), new TypeReference<List<String>>() {});
    var stepViews =
        plan.steps().stream()
            .map(
                step ->
                    new AgentTaskView.PlanStepView(
                        step.stepId(),
                        step.toolId(),
                        step.riskClass(),
                        step.targetUrl(),
                        step.input() == null
                            ? null
                            : new AgentTaskView.StepInputView(
                                step.input().targetRef(),
                                step.input().targetRevision(),
                                step.input().payloadHash(),
                                step.input().payloadLength(),
                                step.input().dataClass(),
                                step.input().scrollDeltaY(),
                                step.input().waitCondition(),
                                step.input().timeoutMs(),
                                step.input().allowSensitiveTarget(),
                                step.input().maximumAttempts(),
                                step.input().actions().stream()
                                    .map(
                                        action ->
                                            new AgentTaskView.BatchActionInputView(
                                                action.actionId(),
                                                action.toolId(),
                                                action.targetRef(),
                                                action.targetRevision(),
                                                action.payloadHash(),
                                                action.payloadLength(),
                                                action.dataClass(),
                                                action.scrollDeltaY(),
                                                action.waitCondition(),
                                                action.timeoutMs(),
                                                action.allowSensitiveTarget(),
                                                action.maximumAttempts()))
                                    .toList(),
                                step.input().stopOnError()),
                        step.rationale(),
                        step.supportingSources(),
                        step.trustFloor(),
                        step.taintLabels(),
                        step.requiredConfirmation(),
                        step.strategy(),
                        step.requiredStateQuality(),
                        step.verification(),
                        step.capabilityTokenId()))
            .toList();
    var eventViews =
        events.stream()
            .map(
                event ->
                    new AgentTaskView.SecurityEventView(
                        event.eventId(),
                        event.eventType(),
                        event.severity(),
                        event.decision(),
                        event.ruleCode(),
                        event.sourceType(),
                        event.contentHash(),
                        event.createdAt()))
            .toList();
    var executionViews =
        executionResults.stream()
            .map(
                result ->
                    new AgentTaskView.ToolExecutionResultView(
                        result.stepId(),
                        result.toolId(),
                        result.status(),
                        result.resultHash(),
                        result.output(),
                        result.verification(),
                        result.completedAt()))
            .toList();
    return new AgentTaskView(
        entity.getTaskId(),
        entity.getSessionId(),
        entity.getGoal(),
        TaskState.valueOf(entity.getState()),
        RiskClass.valueOf(entity.getRiskClass()),
        IntentDecision.valueOf(entity.getIntentDecision()),
        entity.getBlockedReason(),
        entity.getAgentPolicy(),
        entity.getCurrentStep(),
        plan.steps().size(),
        entity.getReplanCount(),
        new AgentTaskView.StepExecutionView(
            entity.getPendingStepId(),
            entity.getPendingToolId() == null ? null : ToolId.valueOf(entity.getPendingToolId()),
            entity.getPendingStateVersion(),
            entity.getPendingContentHash(),
            entity.getStepDeadlineAt(),
            entity.getExecutorLeaseUntil(),
            entity.getReplanReason()),
        new AgentTaskView.ExecutionWaitView(
            entity.getExecutionWaitReason(), entity.getExecutionWaitSince()),
        new AgentTaskView.ConfirmationView(
            entity.getConfirmationId(),
            entity.getConfirmationStatus(),
            entity.getConfirmationExpiresAt(),
            entity.getConfirmationDecidedAt(),
            entity.getConfirmationActorId(),
            entity.getConfirmationEvidenceHash()),
        new AgentTaskView.HumanHandoffView(
            entity.getHandoffRequestId(),
            entity.getHandoffStatus(),
            entity.getHandoffExpiresAt(),
            entity.getHandoffActorId()),
        entity.getChallengeEventId(),
        new io.browsercloud.api.AgentReviewerModels.AgentReviewView(
            entity.getReviewerReviewId(),
            entity.getReviewerStatus(),
            entity.getReviewerDecision() == null
                ? null
                : io.browsercloud.api.AgentReviewerModels.ReviewerDecision.valueOf(
                    entity.getReviewerDecision()),
            read(entity.getReviewerReasonCodes(), new TypeReference<List<String>>() {}),
            entity.getReviewedPlanHash(),
            entity.getReviewerDeploymentId(),
            entity.getReviewerModelName(),
            entity.getReviewerModelRevision(),
            entity.getReviewerInputTokens(),
            entity.getReviewerOutputTokens(),
            entity.getReviewerCostMicros(),
            entity.getReviewerLatencyMs(),
            entity.getReviewerFailureCode(),
            entity.getReviewerCompletedAt()),
        domains,
        new AgentTaskView.PlanView(
            plan.intentId(), stepViews, plan.maxActions(), plan.replanBudget(), plan.expiresAt()),
        entity.getOperationId(),
        executionViews,
        entity.getLastError(),
        eventViews,
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private static List<String> normalizeDomains(List<String> values) {
    var normalized = new LinkedHashSet<String>();
    for (var value : values) {
      var domain = IDN.toASCII(value.trim(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
      while (domain.endsWith(".")) {
        domain = domain.substring(0, domain.length() - 1);
      }
      if (domain.isBlank() || domain.contains("..")) {
        throw new InvalidAgentTaskException("Allowed domain is invalid");
      }
      normalized.add(domain);
    }
    return List.copyOf(normalized);
  }

  private static String validateAgentPolicy(
      AgentPolicy policy,
      boolean humanTakeoverEnabled,
      String startUrl,
      List<CreateAgentTaskRequest.ActionRequest> actions,
      int maxActions,
      int replanBudget) {
    if (policy == AgentPolicy.DISABLED) {
      return "AGENT_DISABLED_BY_SESSION_POLICY";
    }
    if (maxActions > policy.maximumMaxActions()) {
      return "AGENT_POLICY_MAX_ACTIONS_EXCEEDED";
    }
    if (replanBudget > policy.maximumReplanBudget()) {
      return "AGENT_POLICY_REPLAN_BUDGET_EXCEEDED";
    }
    if (startUrl != null && !startUrl.isBlank() && !policy.allows(ToolId.NAVIGATE)) {
      return "AGENT_POLICY_NAVIGATION_FORBIDDEN";
    }
    for (var action : actions) {
      if (!policy.allows(action.toolId())) {
        return "AGENT_POLICY_TOOL_FORBIDDEN";
      }
      if (action.toolId() == ToolId.EXECUTE_ACTIONS
          && action.actions().stream().anyMatch(item -> !policy.allows(item.toolId()))) {
        return "AGENT_POLICY_BATCH_TOOL_FORBIDDEN";
      }
      if (action.toolId() == ToolId.REQUEST_HUMAN_TAKEOVER && !humanTakeoverEnabled) {
        return "HUMAN_TAKEOVER_DISABLED";
      }
    }
    return "";
  }

  private static RiskClass requestedActionRisk(
      List<CreateAgentTaskRequest.ActionRequest> requestedActions) {
    var risk = RiskClass.R0_READ_ONLY;
    for (var action :
        requestedActions == null
            ? List.<CreateAgentTaskRequest.ActionRequest>of()
            : requestedActions) {
      var actionRisk =
          switch (action.toolId()) {
            case TYPE_TEXT, FILL, PASTE_AGENT_CLIPBOARD -> RiskClass.R2_DATA_CHANGE;
            case CLICK_TARGET, SCROLL -> RiskClass.R1_LOW_RISK_CHANGE;
            case WAIT_FOR, REQUEST_HUMAN_TAKEOVER -> RiskClass.R0_READ_ONLY;
            case EXECUTE_ACTIONS ->
                action.actions().stream()
                    .map(
                        item ->
                            isTextInput(item.toolId())
                                ? RiskClass.R2_DATA_CHANGE
                                : item.toolId() == ToolId.WAIT_FOR
                                    ? RiskClass.R0_READ_ONLY
                                    : RiskClass.R1_LOW_RISK_CHANGE)
                    .reduce(RiskClass.R0_READ_ONLY, AgentApplicationService::maxRisk);
            default -> RiskClass.R5_SECURITY;
          };
      risk = maxRisk(risk, actionRisk);
    }
    return risk;
  }

  private static String domainOf(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    try {
      var uri = URI.create(url.trim());
      var scheme = uri.getScheme();
      if ((scheme == null
              || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http")))
          || uri.getHost() == null
          || uri.getUserInfo() != null) {
        return null;
      }
      return IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private String resolveAuthorizedDomain(String startUrl, String sessionId) {
    var requestedDomain = domainOf(startUrl);
    if (requestedDomain != null) {
      return requestedDomain;
    }
    return stateRepository
        .find(sessionId)
        .map(snapshot -> domainOf(snapshot.state().url()))
        .orElse("");
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to persist Agent task", exception);
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to read Agent task", exception);
    }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to read Agent task", exception);
    }
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  private static RiskClass maxRisk(RiskClass left, RiskClass right) {
    return left.ordinal() >= right.ordinal() ? left : right;
  }

  private AgentClipboardApplicationService requireClipboard() {
    if (agentClipboard == null) {
      throw new InvalidAgentTaskException("AGENT_CLIPBOARD_UNAVAILABLE");
    }
    return agentClipboard;
  }

  private static boolean isTextInput(ToolId toolId) {
    return isSecretInput(toolId) || toolId == ToolId.PASTE_AGENT_CLIPBOARD;
  }

  private static boolean isSecretInput(ToolId toolId) {
    return toolId == ToolId.TYPE_TEXT || toolId == ToolId.FILL;
  }

  public static final class AgentTaskNotFoundException extends RuntimeException {}

  public static final class InvalidAgentTaskException extends RuntimeException {
    public InvalidAgentTaskException(String message) {
      super(message);
    }
  }
}
