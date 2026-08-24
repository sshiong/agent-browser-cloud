package io.browsercloud.application;

import static io.browsercloud.api.AgentBrowserEvaluationModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.OperationFactory;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.ChallengeEventJpaRepository;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL authority and exclusive Operation lifecycle for governed Runtime.evaluate. */
@Service
public class AgentBrowserEvaluationStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final SessionRepository sessions;
  private final BrowserStateRepository browserStates;
  private final BrowserCapacityApplicationService capacity;
  private final ChallengeEventJpaRepository challenges;
  private final OperationRepository operations;
  private final NodeCommandGateway nodeCommands;
  private final AuditApplicationService audit;
  private final EntityManager entityManager;

  public AgentBrowserEvaluationStore(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      SessionRepository sessions,
      BrowserStateRepository browserStates,
      BrowserCapacityApplicationService capacity,
      ChallengeEventJpaRepository challenges,
      OperationRepository operations,
      NodeCommandGateway nodeCommands,
      AuditApplicationService audit,
      EntityManager entityManager) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.sessions = sessions;
    this.browserStates = browserStates;
    this.capacity = capacity;
    this.challenges = challenges;
    this.operations = operations;
    this.nodeCommands = nodeCommands;
    this.audit = audit;
    this.entityManager = entityManager;
  }

  @Transactional
  public EvaluationRecord claim(Claim claim) {
    var session = sessions.requireForUpdate(claim.sessionId());
    if (!session.tenantId().equals(claim.tenantId())) {
      throw new SessionNotFoundException(claim.sessionId());
    }
    if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
      throw new EvaluationRejectedException("SESSION_NOT_RUNNING");
    }
    var existing =
        findByIdempotencyForUpdate(claim.tenantId(), claim.actorId(), claim.idempotencyKey());
    if (existing != null) {
      if (!existing.sessionId().equals(claim.sessionId())
          || !existing.requestHash().equals(claim.requestHash())) {
        throw new EvaluationRejectedException("EVALUATION_IDEMPOTENCY_CONFLICT");
      }
      return existing;
    }
    if (session.nodeId() == null
        || !capacity.nodeHasCapability(
            session.nodeId(), "agentJavascriptEvaluate", "state-fenced-bounded-v1")) {
      throw new EvaluationRejectedException("AGENT_EVALUATE_UNAVAILABLE");
    }
    var snapshot =
        browserStates
            .find(claim.sessionId())
            .filter(value -> value.tenantId().equals(claim.tenantId()))
            .filter(value -> value.contextEpoch() == session.contextEpoch())
            .orElseThrow(() -> new EvaluationRejectedException("BROWSER_STATE_UNAVAILABLE"));
    var state = snapshot.state();
    if (state.stateVersion() != claim.expectedStateVersion()
        || state.targetRevision() != claim.expectedTargetRevision()
        || !state.stateHash().equals(claim.expectedStateHash())
        || !state.activeTabId().equals(claim.expectedActiveTabId())) {
      throw new EvaluationRejectedException("STATE_CURSOR_STALE");
    }
    if (!java.util.Set.of("COMPLETE", "DEPTH_LIMITED").contains(state.stateQuality())) {
      throw new EvaluationRejectedException("BROWSER_STATE_NOT_EXECUTABLE");
    }
    if (!state.nativeDialogs().isEmpty()) {
      throw new EvaluationRejectedException("NATIVE_DIALOG_ACTIVE");
    }
    if (claim.mode() == EvaluationMode.PAGE_ACTION
        && challenges.existsActiveAtState(
            claim.tenantId(), claim.sessionId(), session.contextEpoch(), state.stateVersion())) {
      throw new EvaluationRejectedException("CHALLENGE_DETECTED");
    }
    operations.ensureNoActiveOperation(session.sessionId());
    var operation =
        OperationFactory.agentJavascriptEvaluation(
            session,
            claim.actorId(),
            operations.nextOperationEpoch(session.sessionId()),
            claim.mode() == EvaluationMode.READ_ONLY);
    operations.insert(operation);
    // The evaluation ledger has a real FK to the Operation. Flush the assigned-ID JPA insert
    // before JdbcTemplate executes the ledger insert in the same transaction.
    entityManager.flush();
    var now = Instant.now();
    jdbc.update(
        """
        INSERT INTO agent_browser_javascript_evaluations (
          evaluation_id, tenant_id, session_id, actor_id, idempotency_key, request_hash,
          request_id, operation_id, command_id, node_id, coordinator_term, context_epoch,
          operation_epoch, evaluation_mode, expression_sha256, expression_bytes, await_promise,
          timeout_ms, maximum_result_bytes, expected_state_version, expected_target_revision,
          expected_state_hash, expected_active_tab_id, state, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                  'EXECUTING', ?, ?)
        """,
        claim.evaluationId(),
        claim.tenantId(),
        claim.sessionId(),
        claim.actorId(),
        claim.idempotencyKey(),
        claim.requestHash(),
        claim.requestId(),
        operation.operationId(),
        claim.commandId(),
        session.nodeId(),
        session.coordinatorTerm(),
        session.contextEpoch(),
        operation.operationEpoch(),
        claim.mode().name(),
        claim.expressionSha256(),
        claim.expressionBytes(),
        claim.awaitPromise(),
        claim.timeoutMs(),
        claim.maximumResultBytes(),
        claim.expectedStateVersion(),
        claim.expectedTargetRevision(),
        claim.expectedStateHash(),
        claim.expectedActiveTabId(),
        Timestamp.from(now),
        Timestamp.from(now));
    nodeCommands.send(
        NodeCommands.agentBrowserEvaluate(
            session,
            operation,
            claim.evaluationId(),
            claim.mode().name(),
            claim.expectedStateVersion(),
            claim.expectedTargetRevision(),
            claim.expectedStateHash(),
            claim.expectedActiveTabId(),
            claim.sealedExpression(),
            claim.awaitPromise(),
            claim.timeoutMs(),
            claim.maximumResultBytes(),
            claim.commandId()));
    audit.append(
        new AuditApplicationService.AuditRecord(
            claim.tenantId(),
            claim.sessionId(),
            "AGENT_BROWSER_JAVASCRIPT_EVALUATION",
            "AGENT",
            claim.actorId(),
            "JAVASCRIPT_EVALUATION",
            claim.evaluationId(),
            "RUNTIME_EVALUATE",
            "ACCEPTED",
            Map.of(
                "operationId", operation.operationId(),
                "mode", claim.mode().name(),
                "goalHash", claim.goalHash(),
                "expressionSha256", claim.expressionSha256(),
                "expressionBytes", claim.expressionBytes(),
                "awaitPromise", claim.awaitPromise(),
                "timeoutMs", claim.timeoutMs(),
                "maximumResultBytes", claim.maximumResultBytes(),
                "stateCursorHash", claim.stateCursorHash()),
            claim.requestId()));
    return requireForUpdate(claim.evaluationId(), claim.tenantId(), claim.actorId());
  }

  @Transactional
  public void completed(
      NodeEventReceived envelope, NodeEvent.AgentBrowserEvaluationCompleted completed) {
    var evaluation = requireForUpdate(completed.evaluationId(), envelope.tenantId(), null);
    requireEnvelope(evaluation, envelope);
    if (!"EXECUTING".equals(evaluation.state())) return;
    if (!evaluation.mode().name().equals(completed.mode())) {
      failLocked(evaluation, "EVALUATION_EVENT_MODE_MISMATCH", null, null, 0, envelope.eventId());
      return;
    }
    // A stale rejection intentionally reports the Node's newer authoritative cursor. It is a
    // terminal, side-effect-free failure, not a successful result, so accept it under the exact
    // Operation envelope and let the preceding StateUpdated event refresh the Control Plane.
    if ("STATE_STALE".equals(completed.errorCode())
        || "ACTIVE_TAB_CHANGED".equals(completed.errorCode())) {
      failLocked(
          evaluation,
          completed.errorCode(),
          null,
          null,
          completed.durationMs(),
          envelope.eventId());
      return;
    }
    if (evaluation.expectedStateVersion() != completed.stateVersionBefore()
        || evaluation.expectedTargetRevision() != completed.targetRevisionBefore()
        || !evaluation.expectedStateHash().equals(completed.stateHashBefore())
        || !evaluation.expectedActiveTabId().equals(completed.activeTabIdBefore())) {
      failLocked(evaluation, "EVALUATION_EVENT_FENCE_MISMATCH", null, null, 0, envelope.eventId());
      return;
    }
    if (!completed.errorCode().isBlank()) {
      failLocked(
          evaluation,
          completed.errorCode(),
          emptyToNull(completed.exceptionClass()),
          emptyToNull(completed.exceptionMessage()),
          completed.durationMs(),
          envelope.eventId());
      return;
    }
    try {
      validateCommitted(evaluation, completed);
    } catch (EvaluationRejectedException exception) {
      failLocked(evaluation, "EVALUATION_RESULT_INVALID", null, null, 0, envelope.eventId());
      return;
    }
    var now = Instant.now();
    jdbc.update(
        """
        UPDATE agent_browser_javascript_evaluations
        SET state = 'COMMITTED', result_type = ?, result_json = ?, result_bytes = ?,
            redacted_value_count = ?, state_version_after = ?, target_revision_after = ?,
            state_hash_after = ?, active_tab_id_after = ?, duration_ms = ?, completed_at = ?,
            updated_at = ?, version = version + 1
        WHERE evaluation_id = ? AND state = 'EXECUTING'
        """,
        completed.resultType(),
        completed.resultJson(),
        completed.resultBytes(),
        completed.redactedValueCount(),
        completed.stateVersionAfter(),
        completed.targetRevisionAfter(),
        completed.stateHashAfter(),
        completed.activeTabIdAfter(),
        completed.durationMs(),
        Timestamp.from(now),
        Timestamp.from(now),
        evaluation.evaluationId());
    operations.transition(
        evaluation.operationId(), OperationState.ACTIVE, OperationState.COMMITTED);
    audit.append(
        new AuditApplicationService.AuditRecord(
            evaluation.tenantId(),
            evaluation.sessionId(),
            "AGENT_BROWSER_JAVASCRIPT_EVALUATION",
            "NODE",
            "browser-node",
            "JAVASCRIPT_EVALUATION",
            evaluation.evaluationId(),
            "RUNTIME_EVALUATE",
            "COMMITTED",
            completionAudit(evaluation, completed),
            envelope.eventId()));
  }

  @Transactional(readOnly = true)
  public EvaluationView get(String evaluationId, String tenantId, String actorId) {
    return toView(require(evaluationId, tenantId, actorId));
  }

  @Transactional
  public void failDispatch(String commandId, String errorCode, Instant now) {
    var values =
        jdbc.query(
            "SELECT * FROM agent_browser_javascript_evaluations WHERE command_id = ? FOR UPDATE",
            AgentBrowserEvaluationStore::map,
            commandId);
    if (values.isEmpty() || !"EXECUTING".equals(values.getFirst().state())) return;
    failLocked(values.getFirst(), stableCode(errorCode), null, null, 0, commandId);
  }

  @Scheduled(fixedDelayString = "${agent-browser.evaluation-reconcile-ms:5000}")
  @Transactional
  public void reconcileExpired() {
    var cutoff = Timestamp.from(Instant.now().minusSeconds(660));
    var expired =
        jdbc.query(
            """
            SELECT * FROM agent_browser_javascript_evaluations
            WHERE state = 'EXECUTING' AND created_at < ?
            ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED
            """,
            AgentBrowserEvaluationStore::map,
            cutoff);
    expired.forEach(
        value -> failLocked(value, "EVALUATION_TIMEOUT", null, null, 0, "evaluation-reconciler"));
  }

  private EvaluationRecord failLocked(
      EvaluationRecord evaluation,
      String errorCode,
      String exceptionClass,
      String exceptionMessage,
      int durationMs,
      String requestId) {
    var now = Instant.now();
    jdbc.update(
        """
        UPDATE agent_browser_javascript_evaluations
        SET state = 'FAILED', error_code = ?, exception_class = ?, exception_message = ?,
            duration_ms = ?, completed_at = ?, updated_at = ?, version = version + 1
        WHERE evaluation_id = ? AND state = 'EXECUTING'
        """,
        stableCode(errorCode),
        bounded(exceptionClass, 256),
        bounded(exceptionMessage, 2_048),
        Math.max(0, Math.min(durationMs, 30_000)),
        Timestamp.from(now),
        Timestamp.from(now),
        evaluation.evaluationId());
    operations
        .findActive(evaluation.sessionId())
        .filter(value -> value.operationId().equals(evaluation.operationId()))
        .ifPresent(
            value ->
                operations.transition(
                    value.operationId(), OperationState.ACTIVE, OperationState.ABORTED));
    audit.append(
        new AuditApplicationService.AuditRecord(
            evaluation.tenantId(),
            evaluation.sessionId(),
            "AGENT_BROWSER_JAVASCRIPT_EVALUATION",
            "SYSTEM",
            "control-plane",
            "JAVASCRIPT_EVALUATION",
            evaluation.evaluationId(),
            "RUNTIME_EVALUATE",
            stableCode(errorCode),
            Map.of(
                "operationId", evaluation.operationId(),
                "mode", evaluation.mode().name(),
                "expressionSha256", evaluation.expressionSha256(),
                "expressionBytes", evaluation.expressionBytes()),
            requestId));
    return requireForUpdate(evaluation.evaluationId(), evaluation.tenantId(), null);
  }

  private static void requireEnvelope(EvaluationRecord value, NodeEventReceived envelope) {
    if (!value.sessionId().equals(envelope.sessionId())
        || value.coordinatorTerm() != envelope.coordinatorTerm()
        || value.contextEpoch() != envelope.contextEpoch()
        || value.operationEpoch() != envelope.operationEpoch()) {
      throw new EvaluationRejectedException("EVALUATION_EVENT_FENCE_STALE");
    }
  }

  private void validateCommitted(
      EvaluationRecord evaluation, NodeEvent.AgentBrowserEvaluationCompleted completed) {
    if (completed.resultType().isBlank()
        || completed.resultType().length() > 64
        || completed.resultBytes() < 0
        || completed.resultBytes() > evaluation.maximumResultBytes()
        || completed.redactedValueCount() < 0
        || completed.redactedValueCount() > 10_000
        || completed.resultJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            != completed.resultBytes()
        || completed.stateVersionAfter() < evaluation.expectedStateVersion()
        || completed.targetRevisionAfter() < 1
        || !completed.stateHashAfter().matches("^[0-9a-f]{64}$")
        || completed.activeTabIdAfter().isBlank()
        || completed.activeTabIdAfter().length() > 128
        || completed.durationMs() < 0
        || completed.durationMs() > 30_000) {
      throw new EvaluationRejectedException("EVALUATION_RESULT_INVALID");
    }
    try {
      objectMapper.readTree(completed.resultJson());
    } catch (JsonProcessingException exception) {
      throw new EvaluationRejectedException("EVALUATION_RESULT_INVALID");
    }
  }

  private static Map<String, Object> completionAudit(
      EvaluationRecord evaluation, NodeEvent.AgentBrowserEvaluationCompleted completed) {
    var details = new LinkedHashMap<String, Object>();
    details.put("operationId", evaluation.operationId());
    details.put("mode", evaluation.mode().name());
    details.put("expressionSha256", evaluation.expressionSha256());
    details.put("expressionBytes", evaluation.expressionBytes());
    details.put("resultType", completed.resultType());
    details.put("resultBytes", completed.resultBytes());
    details.put("redactedValueCount", completed.redactedValueCount());
    details.put("stateVersionBefore", completed.stateVersionBefore());
    details.put("stateVersionAfter", completed.stateVersionAfter());
    details.put("durationMs", completed.durationMs());
    return Map.copyOf(details);
  }

  private EvaluationRecord findByIdempotencyForUpdate(
      String tenantId, String actorId, String idempotencyKey) {
    var values =
        jdbc.query(
            "SELECT * FROM agent_browser_javascript_evaluations WHERE tenant_id = ? AND actor_id = ? AND idempotency_key = ? FOR UPDATE",
            AgentBrowserEvaluationStore::map,
            tenantId,
            actorId,
            idempotencyKey);
    return values.isEmpty() ? null : values.getFirst();
  }

  private EvaluationRecord requireForUpdate(String evaluationId, String tenantId, String actorId) {
    var sql =
        actorId == null
            ? "SELECT * FROM agent_browser_javascript_evaluations WHERE evaluation_id = ? AND tenant_id = ? FOR UPDATE"
            : "SELECT * FROM agent_browser_javascript_evaluations WHERE evaluation_id = ? AND tenant_id = ? AND actor_id = ? FOR UPDATE";
    var values =
        actorId == null
            ? jdbc.query(sql, AgentBrowserEvaluationStore::map, evaluationId, tenantId)
            : jdbc.query(sql, AgentBrowserEvaluationStore::map, evaluationId, tenantId, actorId);
    if (values.isEmpty()) throw new EvaluationNotFoundException();
    return values.getFirst();
  }

  private EvaluationRecord require(String evaluationId, String tenantId, String actorId) {
    var values =
        jdbc.query(
            "SELECT * FROM agent_browser_javascript_evaluations WHERE evaluation_id = ? AND tenant_id = ? AND actor_id = ?",
            AgentBrowserEvaluationStore::map,
            evaluationId,
            tenantId,
            actorId);
    if (values.isEmpty()) throw new EvaluationNotFoundException();
    return values.getFirst();
  }

  private EvaluationView toView(EvaluationRecord value) {
    JsonNode result = null;
    if (value.resultJson() != null) {
      try {
        result = objectMapper.readTree(value.resultJson());
      } catch (JsonProcessingException exception) {
        throw new EvaluationRejectedException("EVALUATION_RESULT_INVALID");
      }
    }
    return new EvaluationView(
        value.evaluationId(),
        value.sessionId(),
        value.mode(),
        value.state(),
        cursor(
            value.expectedStateVersion(),
            value.expectedTargetRevision(),
            value.expectedStateHash()),
        value.stateVersionAfter() == null
            ? null
            : cursor(
                value.stateVersionAfter(), value.targetRevisionAfter(), value.stateHashAfter()),
        value.expectedActiveTabId(),
        value.activeTabIdAfter(),
        value.expressionSha256(),
        value.expressionBytes(),
        value.awaitPromise(),
        value.timeoutMs(),
        value.maximumResultBytes(),
        value.resultType(),
        result,
        value.resultBytes(),
        value.redactedValueCount(),
        value.exceptionClass(),
        value.exceptionMessage(),
        value.errorCode(),
        value.durationMs(),
        value.requestId(),
        value.createdAt(),
        value.updatedAt(),
        value.completedAt());
  }

  private static EvaluationRecord map(ResultSet row, int ignored) throws SQLException {
    return new EvaluationRecord(
        row.getString("evaluation_id"),
        row.getString("tenant_id"),
        row.getString("session_id"),
        row.getString("actor_id"),
        row.getString("request_hash"),
        row.getString("request_id"),
        row.getString("operation_id"),
        row.getString("command_id"),
        row.getString("node_id"),
        row.getLong("coordinator_term"),
        row.getLong("context_epoch"),
        row.getLong("operation_epoch"),
        EvaluationMode.valueOf(row.getString("evaluation_mode")),
        row.getString("expression_sha256"),
        row.getInt("expression_bytes"),
        row.getBoolean("await_promise"),
        row.getInt("timeout_ms"),
        row.getInt("maximum_result_bytes"),
        row.getLong("expected_state_version"),
        row.getLong("expected_target_revision"),
        row.getString("expected_state_hash"),
        row.getString("expected_active_tab_id"),
        row.getString("state"),
        row.getString("result_type"),
        row.getString("result_json"),
        nullableInt(row, "result_bytes"),
        nullableInt(row, "redacted_value_count"),
        row.getString("exception_class"),
        row.getString("exception_message"),
        row.getString("error_code"),
        nullableLong(row, "state_version_after"),
        nullableLong(row, "target_revision_after"),
        row.getString("state_hash_after"),
        row.getString("active_tab_id_after"),
        nullableInt(row, "duration_ms"),
        row.getTimestamp("created_at").toInstant(),
        row.getTimestamp("updated_at").toInstant(),
        nullableInstant(row, "completed_at"));
  }

  private static Integer nullableInt(ResultSet row, String column) throws SQLException {
    var value = row.getInt(column);
    return row.wasNull() ? null : value;
  }

  private static Long nullableLong(ResultSet row, String column) throws SQLException {
    var value = row.getLong(column);
    return row.wasNull() ? null : value;
  }

  private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
    var value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String cursor(long stateVersion, long targetRevision, String stateHash) {
    return stateVersion + ":" + targetRevision + ":" + stateHash;
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String bounded(String value, int maximum) {
    if (value == null || value.isBlank()) return null;
    return value.length() <= maximum ? value : value.substring(0, maximum);
  }

  private static String stableCode(String value) {
    return value != null && value.matches("^[A-Z][A-Z0-9_]{2,127}$") ? value : "EVALUATION_FAILED";
  }

  public record Claim(
      String evaluationId,
      String tenantId,
      String sessionId,
      String actorId,
      String idempotencyKey,
      String requestHash,
      String requestId,
      String commandId,
      EvaluationMode mode,
      String goalHash,
      String expressionSha256,
      int expressionBytes,
      String sealedExpression,
      boolean awaitPromise,
      int timeoutMs,
      int maximumResultBytes,
      long expectedStateVersion,
      long expectedTargetRevision,
      String expectedStateHash,
      String expectedActiveTabId,
      String stateCursorHash) {}

  public record EvaluationRecord(
      String evaluationId,
      String tenantId,
      String sessionId,
      String actorId,
      String requestHash,
      String requestId,
      String operationId,
      String commandId,
      String nodeId,
      long coordinatorTerm,
      long contextEpoch,
      long operationEpoch,
      EvaluationMode mode,
      String expressionSha256,
      int expressionBytes,
      boolean awaitPromise,
      int timeoutMs,
      int maximumResultBytes,
      long expectedStateVersion,
      long expectedTargetRevision,
      String expectedStateHash,
      String expectedActiveTabId,
      String state,
      String resultType,
      String resultJson,
      Integer resultBytes,
      Integer redactedValueCount,
      String exceptionClass,
      String exceptionMessage,
      String errorCode,
      Long stateVersionAfter,
      Long targetRevisionAfter,
      String stateHashAfter,
      String activeTabIdAfter,
      Integer durationMs,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt) {}

  public static final class EvaluationRejectedException extends RuntimeException {
    public EvaluationRejectedException(String code) {
      super(code);
    }
  }

  public static final class EvaluationNotFoundException extends RuntimeException {}
}
