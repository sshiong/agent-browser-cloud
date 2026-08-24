package io.browsercloud.application;

import static io.browsercloud.api.AgentBrowserEvaluationModels.*;

import io.browsercloud.application.AgentBrowserEvaluationStore.Claim;
import io.browsercloud.application.AgentBrowserEvaluationStore.EvaluationNotFoundException;
import io.browsercloud.application.AgentBrowserEvaluationStore.EvaluationRejectedException;
import io.browsercloud.domain.agent.AgentModels.IntentDecision;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

/** Validates intent and source policy before sealing and dispatching one governed evaluation. */
@Service
public class AgentBrowserEvaluationApplicationService {
  private static final int MAX_WAITERS = 32;
  private static final List<String> FORBIDDEN_BROWSER_SOURCES =
      List.of(
          "document.cookie",
          "cookiestore",
          "localstorage",
          "sessionstorage",
          "indexeddb",
          "navigator.credentials",
          "navigator.clipboard",
          "fetch(",
          "xmlhttprequest",
          "websocket",
          "eventsource",
          "sendbeacon",
          "serviceworker",
          "new worker",
          "new sharedworker",
          "window.open",
          "location.assign",
          "location.replace",
          "history.pushstate",
          "history.replacestate",
          "chrome.",
          "devtools");

  private final AgentBrowserEvaluationStore store;
  private final AgentBrowserPerceptionService perception;
  private final AgentControlPolicyService controlPolicy;
  private final PromptSecurityService promptSecurity;
  private final AgentActionPayloadService payloads;
  private final Semaphore waiters = new Semaphore(MAX_WAITERS);

  public AgentBrowserEvaluationApplicationService(
      AgentBrowserEvaluationStore store,
      AgentBrowserPerceptionService perception,
      AgentControlPolicyService controlPolicy,
      PromptSecurityService promptSecurity,
      AgentActionPayloadService payloads) {
    this.store = store;
    this.perception = perception;
    this.controlPolicy = controlPolicy;
    this.promptSecurity = promptSecurity;
    this.payloads = payloads;
  }

  public EvaluationView create(
      String sessionId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      CreateEvaluationRequest request) {
    var expression = request.expression().strip();
    validateExpression(expression);
    var policy = controlPolicy.require(sessionId, tenantId);
    var intent = promptSecurity.evaluate(request.goal(), List.of(), policy.mode());
    if (intent.decision() == IntentDecision.FORBIDDEN) {
      throw new EvaluationRejectedException("EVALUATION_PERMISSION_DENIED");
    }
    if (intent.decision() == IntentDecision.CONFIRM_REQUIRED) {
      throw new EvaluationRejectedException("HIGH_RISK_CONFIRMATION_REQUIRED");
    }
    var snapshot = perception.snapshot(sessionId, tenantId);
    if (!snapshot.stateCursor().equals(request.expectedStateCursor())) {
      throw new EvaluationRejectedException("STATE_CURSOR_STALE");
    }
    if (snapshot.activeTab() == null || snapshot.activeTab().tabId().isBlank()) {
      throw new EvaluationRejectedException("ACTIVE_TAB_UNAVAILABLE");
    }
    var cursor = parseCursor(request.expectedStateCursor());
    var awaitPromise = request.awaitPromise() == null || request.awaitPromise();
    var timeoutMs = request.timeoutMs() == null ? 2_000 : request.timeoutMs();
    var maximumResultBytes =
        request.maximumResultBytes() == null ? 16_384 : request.maximumResultBytes();
    var expressionSha256 = PromptSecurityService.sha256(expression);
    var expressionBytes = expression.getBytes(StandardCharsets.UTF_8).length;
    if (expressionBytes > 16_384) {
      throw new EvaluationRejectedException("EVALUATION_EXPRESSION_TOO_LARGE");
    }
    var evaluationId = id("aje_");
    var commandId = id("cmd_");
    var requestHash =
        PromptSecurityService.sha256(
            String.join(
                "|",
                sessionId,
                request.mode().name(),
                request.expectedStateCursor(),
                snapshot.activeTab().tabId(),
                expressionSha256,
                Boolean.toString(awaitPromise),
                Integer.toString(timeoutMs),
                Integer.toString(maximumResultBytes)));
    var claimed =
        store.claim(
            new Claim(
                evaluationId,
                tenantId,
                sessionId,
                actorId,
                idempotencyKey,
                requestHash,
                requestId,
                commandId,
                request.mode(),
                PromptSecurityService.sha256(request.goal()),
                expressionSha256,
                expressionBytes,
                payloads.seal(tenantId, evaluationId, "expression", expression),
                awaitPromise,
                timeoutMs,
                maximumResultBytes,
                cursor.stateVersion(),
                cursor.targetRevision(),
                cursor.stateHash(),
                snapshot.activeTab().tabId(),
                PromptSecurityService.sha256(request.expectedStateCursor())));
    return store.get(claimed.evaluationId(), tenantId, actorId);
  }

  /** Bounded wait reads only PostgreSQL and never polls Chromium. */
  public EvaluationView get(
      String sessionId, String evaluationId, String tenantId, String actorId, int waitMs) {
    if (waitMs > 0 && !waiters.tryAcquire()) {
      throw new EvaluationRejectedException("EVALUATION_WAIT_CAPACITY_EXCEEDED");
    }
    try {
      var deadline = Instant.now().plus(Duration.ofMillis(waitMs));
      while (true) {
        var value = store.get(evaluationId, tenantId, actorId);
        if (!value.sessionId().equals(sessionId)) throw new EvaluationNotFoundException();
        if (!value.state().equals("EXECUTING") || waitMs == 0) return value;
        if (!Instant.now().isBefore(deadline)) {
          throw new EvaluationRejectedException("EVALUATION_WAIT_TIMEOUT");
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new EvaluationRejectedException("EVALUATION_WAIT_INTERRUPTED");
        }
      }
    } finally {
      if (waitMs > 0) waiters.release();
    }
  }

  private static void validateExpression(String expression) {
    if (expression.isBlank()) {
      throw new EvaluationRejectedException("EVALUATION_EXPRESSION_INVALID");
    }
    if (expression
        .chars()
        .anyMatch(
            value ->
                Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t')) {
      throw new EvaluationRejectedException("EVALUATION_EXPRESSION_INVALID");
    }
    var normalized = expression.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    if (FORBIDDEN_BROWSER_SOURCES.stream()
        .map(value -> value.replaceAll("\\s+", ""))
        .anyMatch(normalized::contains)) {
      throw new EvaluationRejectedException("EVALUATION_FORBIDDEN_BROWSER_SOURCE");
    }
  }

  private static StateCursor parseCursor(String value) {
    var parts = value.split(":", 3);
    try {
      var stateVersion = Long.parseLong(parts[0]);
      var targetRevision = Long.parseLong(parts[1]);
      if (parts.length != 3 || stateVersion < 1 || targetRevision < 1) {
        throw new NumberFormatException();
      }
      return new StateCursor(stateVersion, targetRevision, parts[2]);
    } catch (RuntimeException exception) {
      throw new EvaluationRejectedException("STATE_CURSOR_INVALID");
    }
  }

  private static String id(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private record StateCursor(long stateVersion, long targetRevision, String stateHash) {}
}
