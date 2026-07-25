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
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 单 Planner 安全 MVP：只生成受限 Navigation / State Read 计划，不宣称执行。 */
@Service
public class AgentApplicationService {

  private static final int DEFAULT_MAX_ACTIONS = 8;
  private static final int DEFAULT_REPLAN_BUDGET = 1;

  private final AgentTaskJpaRepository repository;
  private final SessionRepository sessionRepository;
  private final BrowserStateRepository stateRepository;
  private final IdempotencyService idempotencyService;
  private final PromptSecurityService promptSecurityService;
  private final AgentCapabilityTokenService capabilityTokenService;
  private final ObjectMapper objectMapper;

  public AgentApplicationService(
      AgentTaskJpaRepository repository,
      SessionRepository sessionRepository,
      BrowserStateRepository stateRepository,
      IdempotencyService idempotencyService,
      PromptSecurityService promptSecurityService,
      AgentCapabilityTokenService capabilityTokenService,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.sessionRepository = sessionRepository;
    this.stateRepository = stateRepository;
    this.idempotencyService = idempotencyService;
    this.promptSecurityService = promptSecurityService;
    this.capabilityTokenService = capabilityTokenService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public AgentTaskView create(
      String sessionId, String tenantId, CreateAgentTaskRequest request, String idempotencyKey) {
    var session = sessionRepository.require(sessionId);
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

    var evaluation = promptSecurityService.evaluate(request.goal(), request.contextSources());
    var allowedDomains = normalizeDomains(request.allowedDomains());
    var now = Instant.now();
    var intentId = newId("int_");
    var maxActions = request.maxActions() == null ? DEFAULT_MAX_ACTIONS : request.maxActions();
    var replanBudget =
        request.replanBudget() == null ? DEFAULT_REPLAN_BUDGET : request.replanBudget();
    var expiresAt = now.plus(5, ChronoUnit.MINUTES);
    var taskRisk =
        request.startUrl() == null || request.startUrl().isBlank()
            ? evaluation.riskClass()
            : maxRisk(evaluation.riskClass(), RiskClass.R1_LOW_RISK_CHANGE);
    var blockReason =
        validatePlanPreconditions(
            session.state(), request.startUrl(), allowedDomains, evaluation, maxActions, sessionId);
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
                maxActions,
                replanBudget,
                expiresAt)
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
    var state = blockReason.isBlank() ? TaskState.PLANNED : TaskState.BLOCKED;
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
            write(allowedDomains),
            write(plan),
            write(securityEvents),
            now);
    repository.save(entity);
    return toView(entity);
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
      String sessionId) {
    if (evaluation.decision() == IntentDecision.FORBIDDEN) {
      return evaluation.reason();
    }
    if (evaluation.decision() == IntentDecision.CONFIRM_REQUIRED) {
      return "HUMAN_CONFIRMATION_FLOW_NOT_IMPLEMENTED";
    }
    if (evaluation.riskClass().ordinal() > RiskClass.R1_LOW_RISK_CHANGE.ordinal()) {
      return "PLANNER_UNSUPPORTED_MUTATING_GOAL";
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
    var neededActions = targetDomain == null ? 3 : 4;
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
    } else if (targetDomain == null) {
      return "STATE_UNAVAILABLE";
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
      int maxActions,
      int replanBudget,
      Instant expiresAt) {
    var steps = new ArrayList<PlanStep>();
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
            "Read stable browser state before any semantic action",
            authorizedDomain,
            targetDomain == null
                ? "COMPLETE_OR_DEPTH_LIMITED"
                : "COMPLETE_OR_DEPTH_LIMITED_AFTER_NAVIGATION",
            "STATE_VERSION_PRESENT",
            expiresAt));
    steps.add(
        step(
            tenantId,
            sessionId,
            operationId,
            intentId,
            ToolId.GET_URL,
            RiskClass.R0_READ_ONLY,
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
            "Return a bounded data-only page summary",
            authorizedDomain,
            "COMPLETE_OR_DEPTH_LIMITED",
            "SUMMARY_SCHEMA_VALID",
            expiresAt));
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
            toolId == ToolId.NAVIGATE ? "NAVIGATION" : "BROWSER_STATE_METADATA",
            riskClass,
            expiresAt);
    return new PlanStep(
        newId("step_"),
        toolId,
        riskClass,
        targetUrl,
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
        entity.getCurrentStep(),
        plan.steps().size(),
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

  public static final class AgentTaskNotFoundException extends RuntimeException {}

  public static final class InvalidAgentTaskException extends RuntimeException {
    public InvalidAgentTaskException(String message) {
      super(message);
    }
  }
}
