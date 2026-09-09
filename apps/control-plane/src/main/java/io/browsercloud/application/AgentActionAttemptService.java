package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Persists hash-only action attempts and blocks a third consecutive no-progress repetition. */
@Service
public class AgentActionAttemptService {

  static final int MAXIMUM_CONSECUTIVE_ATTEMPTS = 2;

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public AgentActionAttemptService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  public Reservation reserve(
      String tenantId,
      String sessionId,
      String taskId,
      String operationId,
      PlanStep step,
      long baseStateVersion,
      String baseStateHash,
      Instant now) {
    if (step.toolId() == ToolId.WAIT_FOR) return Reservation.disabled();
    // Serialize the read/decision/insert sequence even if a future caller does not already hold
    // AgentExecutionService's task lock.
    jdbc.queryForObject(
        "SELECT task_id FROM agent_tasks WHERE task_id = ? AND tenant_id = ? FOR UPDATE",
        String.class,
        taskId,
        tenantId);
    var descriptorHash = descriptorHash(step);
    var signature =
        PromptSecurityService.sha256(
            descriptorHash + ":" + (baseStateHash == null ? "" : baseStateHash));
    var previous =
        jdbc.query(
            """
            SELECT attempt_signature, consecutive_count
              FROM agent_action_attempts
             WHERE task_id = ? AND tenant_id = ?
               AND status IN ('DISPATCHED', 'VERIFIED', 'FAILED', 'LOOP_BLOCKED')
             ORDER BY created_at DESC, attempt_id DESC
             LIMIT 1
            """,
            (result, row) ->
                new PreviousAttempt(
                    result.getString("attempt_signature"), result.getInt("consecutive_count")),
            taskId,
            tenantId);
    var consecutive =
        !previous.isEmpty() && signature.equals(previous.getFirst().signature())
            ? previous.getFirst().consecutiveCount() + 1
            : 1;
    var attemptId = "aat_" + UUID.randomUUID().toString().replace("-", "");
    var blocked = consecutive > MAXIMUM_CONSECUTIVE_ATTEMPTS;
    jdbc.update(
        """
        INSERT INTO agent_action_attempts (
          attempt_id, tenant_id, session_id, task_id, operation_id, step_id, tool_id,
          action_descriptor_hash, base_state_version, base_state_hash, attempt_signature,
          consecutive_count, status, failure_code, created_at, completed_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        attemptId,
        tenantId,
        sessionId,
        taskId,
        operationId,
        step.stepId(),
        step.toolId().name(),
        descriptorHash,
        baseStateVersion,
        baseStateHash == null ? "" : baseStateHash,
        signature,
        Math.min(consecutive, MAXIMUM_CONSECUTIVE_ATTEMPTS + 1),
        blocked ? "LOOP_BLOCKED" : "RESERVED",
        blocked ? "AGENT_ACTION_LOOP_DETECTED" : null,
        Timestamp.from(now),
        blocked ? Timestamp.from(now) : null);
    if (blocked) throw new ActionLoopDetectedException();
    return new Reservation(attemptId, true);
  }

  public void dispatched(Reservation reservation) {
    if (!reservation.enabled()) return;
    jdbc.update(
        "UPDATE agent_action_attempts SET status = 'DISPATCHED' WHERE attempt_id = ? AND status = 'RESERVED'",
        reservation.attemptId());
  }

  public void abandoned(Reservation reservation, String failureCode, Instant now) {
    if (!reservation.enabled()) return;
    jdbc.update(
        """
        UPDATE agent_action_attempts
           SET status = 'ABANDONED', failure_code = ?, completed_at = ?
         WHERE attempt_id = ? AND status = 'RESERVED'
        """,
        safeCode(failureCode),
        Timestamp.from(now),
        reservation.attemptId());
  }

  public void verified(
      String taskId,
      String operationId,
      String stepId,
      long resultStateVersion,
      String resultStateHash,
      Instant now) {
    complete(
        taskId, operationId, stepId, "VERIFIED", null, resultStateVersion, resultStateHash, now);
  }

  public void failed(
      String taskId,
      String operationId,
      String stepId,
      String failureCode,
      long resultStateVersion,
      String resultStateHash,
      Instant now) {
    complete(
        taskId,
        operationId,
        stepId,
        "FAILED",
        safeCode(failureCode),
        resultStateVersion,
        resultStateHash,
        now);
  }

  private void complete(
      String taskId,
      String operationId,
      String stepId,
      String status,
      String failureCode,
      long stateVersion,
      String stateHash,
      Instant now) {
    jdbc.update(
        """
        UPDATE agent_action_attempts
           SET status = ?, failure_code = ?, result_state_version = ?, result_state_hash = ?,
               completed_at = ?
         WHERE attempt_id = (
           SELECT attempt_id FROM agent_action_attempts
            WHERE task_id = ? AND operation_id = ? AND step_id = ?
              AND status = 'DISPATCHED'
            ORDER BY created_at DESC, attempt_id DESC
            LIMIT 1
         )
        """,
        status,
        failureCode,
        stateVersion,
        stateHash == null ? "" : stateHash,
        Timestamp.from(now),
        taskId,
        operationId,
        stepId);
  }

  String descriptorHash(PlanStep step) {
    var descriptor = new LinkedHashMap<String, Object>();
    descriptor.put("toolId", step.toolId().name());
    descriptor.put("targetUrl", step.targetUrl());
    descriptor.put("input", safeInput(step.input()));
    return PromptSecurityService.sha256(write(descriptor));
  }

  private Map<String, Object> safeInput(StepInput input) {
    if (input == null) return Map.of();
    var safe = new LinkedHashMap<String, Object>();
    safe.put("targetRef", input.targetRef());
    safe.put("payloadHash", input.payloadHash());
    safe.put("payloadLength", input.payloadLength());
    safe.put("dataClass", input.dataClass() == null ? null : input.dataClass().name());
    safe.put("scrollDeltaY", input.scrollDeltaY());
    safe.put("waitCondition", input.waitCondition() == null ? null : input.waitCondition().name());
    safe.put("timeoutMs", input.timeoutMs());
    safe.put("allowSensitiveTarget", input.allowSensitiveTarget());
    safe.put("stopOnError", input.stopOnError());
    safe.put("tabId", input.tabId());
    safe.put("tabUrl", input.tabUrl());
    safe.put("dialogId", input.dialogId());
    safe.put("endTargetRef", input.endTargetRef());
    safe.put("endElementId", input.endElementId());
    safe.put("key", input.key());
    safe.put("button", input.button());
    safe.put("deltaX", input.deltaX());
    safe.put("deltaY", input.deltaY());
    safe.put("durationMs", input.durationMs());
    safe.put(
        "actions",
        input.actions() == null
            ? List.of()
            : input.actions().stream().map(this::safeAction).toList());
    return safe;
  }

  private Map<String, Object> safeAction(ActionInput input) {
    var safe = new LinkedHashMap<String, Object>();
    safe.put("toolId", input.toolId().name());
    safe.put("targetRef", input.targetRef());
    safe.put("elementId", input.elementId());
    safe.put("payloadHash", input.payloadHash());
    safe.put("payloadLength", input.payloadLength());
    safe.put("dataClass", input.dataClass() == null ? null : input.dataClass().name());
    safe.put("scrollDeltaY", input.scrollDeltaY());
    safe.put("waitCondition", input.waitCondition() == null ? null : input.waitCondition().name());
    safe.put("timeoutMs", input.timeoutMs());
    safe.put("allowSensitiveTarget", input.allowSensitiveTarget());
    safe.put("tabId", input.tabId());
    safe.put("tabUrl", input.tabUrl());
    safe.put("dialogId", input.dialogId());
    safe.put("endTargetRef", input.endTargetRef());
    safe.put("endElementId", input.endElementId());
    safe.put("key", input.key());
    safe.put("button", input.button());
    safe.put("deltaX", input.deltaX());
    safe.put("deltaY", input.deltaY());
    safe.put("durationMs", input.durationMs());
    return safe;
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to hash Agent action descriptor", exception);
    }
  }

  private static String safeCode(String value) {
    return value != null && value.matches("^[A-Z][A-Z0-9_]{1,127}$")
        ? value
        : "TOOL_EXECUTION_FAILED";
  }

  public record Reservation(String attemptId, boolean enabled) {
    static Reservation disabled() {
      return new Reservation("", false);
    }
  }

  private record PreviousAttempt(String signature, int consecutiveCount) {}

  public static final class ActionLoopDetectedException extends RuntimeException {
    public ActionLoopDetectedException() {
      super("AGENT_ACTION_LOOP_DETECTED");
    }
  }
}
