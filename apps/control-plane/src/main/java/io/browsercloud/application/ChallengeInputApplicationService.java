package io.browsercloud.application;

import static io.browsercloud.api.ChallengeModels.*;

import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeCommands;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.OperationFactory;
import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.agent.AgentModels.ActionDataClass;
import io.browsercloud.domain.agent.AgentModels.TaskState;
import io.browsercloud.domain.operation.OperationMode;
import io.browsercloud.domain.operation.OperationPhase;
import io.browsercloud.domain.operation.OperationState;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.AgentTaskJpaRepository;
import io.browsercloud.persistence.ChallengeEventJpaRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operator-supplied OTP that is typed by the Agent into its original paused Session. */
@Service
public class ChallengeInputApplicationService {

  private final JdbcTemplate jdbc;
  private final ChallengeEventJpaRepository challenges;
  private final AgentTaskJpaRepository tasks;
  private final BrowserStateRepository states;
  private final SessionRepository sessions;
  private final OperationRepository operations;
  private final NodeCommandGateway commands;
  private final AgentInputSecretApplicationService secrets;
  private final AgentActionPayloadService payloads;
  private final AgentControlPolicyService policies;
  private final AgentExecutionService agentExecution;
  private final AuditApplicationService audit;

  public ChallengeInputApplicationService(
      JdbcTemplate jdbc,
      ChallengeEventJpaRepository challenges,
      AgentTaskJpaRepository tasks,
      BrowserStateRepository states,
      SessionRepository sessions,
      OperationRepository operations,
      NodeCommandGateway commands,
      AgentInputSecretApplicationService secrets,
      AgentActionPayloadService payloads,
      AgentControlPolicyService policies,
      AgentExecutionService agentExecution,
      AuditApplicationService audit) {
    this.jdbc = jdbc;
    this.challenges = challenges;
    this.tasks = tasks;
    this.states = states;
    this.sessions = sessions;
    this.operations = operations;
    this.commands = commands;
    this.secrets = secrets;
    this.payloads = payloads;
    this.policies = policies;
    this.agentExecution = agentExecution;
    this.audit = audit;
  }

  @Transactional
  public ChallengeInputResponseView submit(
      String eventId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      SubmitChallengeInputResponseRequest request) {
    var event =
        challenges
            .findForUpdate(eventId, tenantId)
            .orElseThrow(HumanAssistApplicationService.ChallengeEventNotFoundException::new);
    var existing = findByIdempotency(tenantId, event.getSessionId(), idempotencyKey);
    if (existing != null) {
      if (!existing.challengeEventId().equals(eventId)
          || !existing.secretId().equals(request.secretId())
          || !existing.actorId().equals(actorId)) {
        throw new ChallengeInputRejectedException("IDEMPOTENCY_KEY_REUSED");
      }
      return existing.view();
    }
    var policy = policies.require(event.getSessionId(), tenantId);
    if (!policy.autonomous()) {
      throw new ChallengeInputRejectedException("AGENT_AUTONOMOUS_MODE_REQUIRED");
    }
    if (!"OTP".equals(event.getSuspectedType())
        || !Set.of("TAKEOVER_REQUIRED", "CONFIRMED").contains(event.getStatus())) {
      throw new ChallengeInputRejectedException("CHALLENGE_INPUT_RESPONSE_NOT_ALLOWED");
    }
    if (!event.getExpiresAt().isAfter(Instant.now())
        || event.getTargetRef() == null
        || event.getTargetRef().isBlank()) {
      throw new ChallengeInputRejectedException("CHALLENGE_INPUT_TARGET_UNAVAILABLE");
    }
    var task =
        tasks
            .findByChallengeEventForUpdate(eventId, tenantId)
            .filter(value -> TaskState.WAITING_FOR_HUMAN.name().equals(value.getState()))
            .orElseThrow(() -> new ChallengeInputRejectedException("AGENT_TASK_NOT_WAITING"));
    var session = sessions.requireForUpdate(event.getSessionId());
    if (!tenantId.equals(session.tenantId())
        || (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED)) {
      throw new ChallengeInputRejectedException("SESSION_NOT_RUNNING");
    }
    var snapshot =
        states
            .find(event.getSessionId())
            .filter(value -> value.tenantId().equals(tenantId))
            .filter(value -> value.contextEpoch() == event.getContextEpoch())
            .orElseThrow(() -> new ChallengeInputRejectedException("CURRENT_STATE_UNAVAILABLE"));
    var state = snapshot.state();
    if (state.stateVersion() != event.getStateVersion()
        || state.targetRevision() != event.getTargetRevision()
        || !Set.of("COMPLETE", "DEPTH_LIMITED").contains(state.stateQuality())) {
      throw new ChallengeInputRejectedException("STALE_CHALLENGE_STATE");
    }
    var target =
        state.targets().stream()
            .filter(value -> value.targetRef().equals(event.getTargetRef()))
            .filter(value -> value.sensitive() && value.visible() && value.enabled())
            .filter(value -> Set.of("textbox", "combobox").contains(value.role()))
            .findFirst()
            .orElseThrow(
                () -> new ChallengeInputRejectedException("CHALLENGE_INPUT_TARGET_UNAVAILABLE"));

    operations.ensureNoActiveOperation(session.sessionId());
    var intentId = id("aci_");
    var stepId = "step_human_" + intentId.substring(4);
    var resolved =
        secrets.consume(
            request.secretId(),
            session.sessionId(),
            tenantId,
            task.getTaskId(),
            ActionDataClass.OTP);
    var sealed = payloads.seal(tenantId, task.getTaskId(), stepId, resolved.plaintext());
    var operation =
        OperationFactory.agentHumanInput(
            session, actorId, operations.nextOperationEpoch(session.sessionId()));
    var now = Instant.now();
    var expiresAt = min(event.getExpiresAt(), now.plusSeconds(60));
    jdbc.update(
        """
        INSERT INTO agent_challenge_input_intents(
          intent_id, tenant_id, session_id, task_id, challenge_event_id, secret_id, purpose,
          target_ref, target_revision, base_state_version, sealed_value, value_length,
          maximum_attempts, idempotency_key, actor_id, request_id, operation_id, step_id,
          state, created_at, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, 'OTP', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EXECUTING', ?, ?)
        """,
        intentId,
        tenantId,
        session.sessionId(),
        task.getTaskId(),
        eventId,
        request.secretId(),
        target.targetRef(),
        state.targetRevision(),
        state.stateVersion(),
        sealed,
        resolved.valueLength(),
        policy.sensitiveInputMaximumAttempts(),
        idempotencyKey,
        actorId,
        requestId,
        operation.operationId(),
        stepId,
        Timestamp.from(now),
        Timestamp.from(expiresAt));
    operations.insert(operation);
    event.inputExecuting(now);
    challenges.save(event);
    commands.send(
        NodeCommands.agentChallengeInput(
            session,
            operation,
            task.getTaskId(),
            stepId,
            target.targetRef(),
            state.targetRevision(),
            sealed,
            state.stateVersion(),
            state.stateHash(),
            policy.sensitiveInputMaximumAttempts()));
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            session.sessionId(),
            "AGENT_HUMAN_INPUT_RESPONSE",
            "HUMAN",
            actorId,
            "CHALLENGE_INPUT_INTENT",
            intentId,
            "PROVIDE_OTP",
            "ACCEPTED",
            Map.of(
                "challengeEventId",
                eventId,
                "taskId",
                task.getTaskId(),
                "purpose",
                "OTP",
                "maximumAttempts",
                policy.sensitiveInputMaximumAttempts(),
                "plaintextStored",
                false),
            requestId));
    return require(intentId).view();
  }

  /**
   * Completes the short input Operation and resumes the original Agent without forcing takeover.
   */
  @Transactional
  public boolean stateUpdated(NodeEventReceived envelope, NodeEvent.StateUpdated state) {
    if (!"AGENT_TYPE_TEXT".equals(state.snapshotKind()) || envelope.operationEpoch() == 0) {
      return false;
    }
    var operation =
        operations
            .findActive(envelope.sessionId())
            .filter(value -> value.operationEpoch() == envelope.operationEpoch())
            .filter(value -> value.mode() == OperationMode.HUMAN_ASSIST)
            .filter(value -> value.allowedCapabilities().contains("challenge.input.once"))
            .orElse(null);
    if (operation == null) return false;
    var intent = findByOperation(operation.operationId(), envelope.tenantId(), "EXECUTING");
    if (intent == null) throw new ChallengeInputRejectedException("STALE_CHALLENGE_INPUT");
    if (state.stateVersion() <= intent.baseStateVersion()
        || !Set.of("COMPLETE", "DEPTH_LIMITED").contains(state.stateQuality())) {
      failIntent(intent, operation.operationId(), "POST_INPUT_STATE_INVALID");
      return true;
    }
    var now = Instant.now();
    jdbc.update(
        """
        UPDATE agent_challenge_input_intents
        SET state='COMMITTED', completed_at=?, error_code=NULL, version=version+1
        WHERE intent_id=? AND state='EXECUTING'
        """,
        Timestamp.from(now),
        intent.intentId());
    var event =
        challenges.findForUpdate(intent.challengeEventId(), envelope.tenantId()).orElseThrow();
    event.resolved(now);
    challenges.save(event);
    operations.transitionPhase(
        operation.operationId(), OperationPhase.EXECUTING, OperationPhase.COMPLETING);
    operations.transition(operation.operationId(), OperationState.ACTIVE, OperationState.COMMITTED);
    auditResult(intent, "COMMITTED", null, envelope.eventId());
    agentExecution.resumeAfterHumanAssist(intent.challengeEventId(), envelope.tenantId());
    return true;
  }

  @Transactional
  public boolean failed(NodeEventReceived envelope, NodeEvent.AgentActionFailed failure) {
    if (!failure.stepId().startsWith("step_human_")) return false;
    var operation =
        operations
            .findActive(envelope.sessionId())
            .filter(value -> value.operationEpoch() == envelope.operationEpoch())
            .filter(value -> value.mode() == OperationMode.HUMAN_ASSIST)
            .orElse(null);
    if (operation == null) return false;
    var intent = findByOperation(operation.operationId(), envelope.tenantId(), "EXECUTING");
    if (intent == null
        || !intent.taskId().equals(failure.taskId())
        || !intent.stepId().equals(failure.stepId())) {
      throw new ChallengeInputRejectedException("STALE_CHALLENGE_INPUT");
    }
    failIntent(intent, operation.operationId(), safeCode(failure.errorCode()));
    return true;
  }

  @Scheduled(fixedDelayString = "${agent.challenge-input-expiry-scan-ms:15000}")
  @Transactional
  public void expire() {
    var rows =
        jdbc.query(
            """
            SELECT * FROM agent_challenge_input_intents
            WHERE state='EXECUTING' AND expires_at <= now()
            ORDER BY expires_at, intent_id LIMIT 100 FOR UPDATE SKIP LOCKED
            """,
            this::intent);
    for (var intent : rows) {
      failIntent(intent, intent.operationId(), "CHALLENGE_INPUT_EXPIRED");
    }
  }

  private void failIntent(Intent intent, String operationId, String errorCode) {
    var now = Instant.now();
    jdbc.update(
        """
        UPDATE agent_challenge_input_intents
        SET state=?, completed_at=?, error_code=?, version=version+1
        WHERE intent_id=? AND state='EXECUTING'
        """,
        "CHALLENGE_INPUT_EXPIRED".equals(errorCode) ? "EXPIRED" : "FAILED",
        Timestamp.from(now),
        safeCode(errorCode),
        intent.intentId());
    challenges
        .findForUpdate(intent.challengeEventId(), intent.tenantId())
        .ifPresent(
            event -> {
              if ("EXECUTING".equals(event.getStatus())) {
                event.inputAttemptFailed(now);
                challenges.save(event);
              }
            });
    operations
        .findActive(intent.sessionId())
        .filter(value -> value.operationId().equals(operationId))
        .ifPresent(
            value ->
                operations.transition(
                    value.operationId(), OperationState.ACTIVE, OperationState.ABORTED));
    // The original assistance request is the sole operator-facing notification. A local typing
    // failure leaves the same Challenge retryable, so record it without the generic FAILED result
    // that the notification projector treats as a new high-signal incident.
    auditResult(
        intent, "RETRY_AVAILABLE", safeCode(errorCode), "challenge-input:" + intent.intentId());
  }

  private void auditResult(Intent intent, String result, String errorCode, String requestId) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            intent.tenantId(),
            intent.sessionId(),
            "AGENT_HUMAN_INPUT_RESULT",
            "SYSTEM",
            "agent-human-input",
            "CHALLENGE_INPUT_INTENT",
            intent.intentId(),
            "TYPE_OTP",
            result,
            errorCode == null
                ? Map.of("challengeEventId", intent.challengeEventId(), "plaintextStored", false)
                : Map.of(
                    "challengeEventId",
                    intent.challengeEventId(),
                    "errorCode",
                    errorCode,
                    "plaintextStored",
                    false),
            requestId));
  }

  private Intent findByIdempotency(String tenantId, String sessionId, String key) {
    return jdbc
        .query(
            "SELECT * FROM agent_challenge_input_intents WHERE tenant_id=? AND session_id=? AND idempotency_key=?",
            this::intent,
            tenantId,
            sessionId,
            key)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private Intent findByOperation(String operationId, String tenantId, String state) {
    return jdbc
        .query(
            "SELECT * FROM agent_challenge_input_intents WHERE operation_id=? AND tenant_id=? AND state=? FOR UPDATE",
            this::intent,
            operationId,
            tenantId,
            state)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private Intent require(String intentId) {
    return jdbc
        .query(
            "SELECT * FROM agent_challenge_input_intents WHERE intent_id=?", this::intent, intentId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ChallengeInputRejectedException("CHALLENGE_INPUT_NOT_FOUND"));
  }

  private Intent intent(ResultSet result, int ignored) throws SQLException {
    return new Intent(
        result.getString("intent_id"),
        result.getString("tenant_id"),
        result.getString("session_id"),
        result.getString("task_id"),
        result.getString("challenge_event_id"),
        result.getString("secret_id"),
        result.getString("purpose"),
        result.getString("actor_id"),
        result.getString("step_id"),
        result.getString("state"),
        result.getInt("maximum_attempts"),
        result.getLong("base_state_version"),
        result.getString("operation_id"),
        result.getTimestamp("expires_at").toInstant(),
        result.getTimestamp("created_at").toInstant(),
        result.getTimestamp("completed_at") == null
            ? null
            : result.getTimestamp("completed_at").toInstant(),
        result.getString("error_code"));
  }

  private static Instant min(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private static String safeCode(String value) {
    var code =
        value == null
            ? "CHALLENGE_INPUT_FAILED"
            : value.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
    return code.matches("^[A-Z][A-Z0-9_]{2,127}$") ? code : "CHALLENGE_INPUT_FAILED";
  }

  private static String id(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private record Intent(
      String intentId,
      String tenantId,
      String sessionId,
      String taskId,
      String challengeEventId,
      String secretId,
      String purpose,
      String actorId,
      String stepId,
      String state,
      int maximumAttempts,
      long baseStateVersion,
      String operationId,
      Instant expiresAt,
      Instant createdAt,
      Instant completedAt,
      String errorCode) {
    ChallengeInputResponseView view() {
      return new ChallengeInputResponseView(
          intentId,
          challengeEventId,
          sessionId,
          taskId,
          purpose,
          state,
          maximumAttempts,
          operationId,
          expiresAt,
          createdAt,
          completedAt,
          errorCode);
    }
  }

  public static final class ChallengeInputRejectedException extends RuntimeException {
    public ChallengeInputRejectedException(String message) {
      super(message);
    }
  }
}
