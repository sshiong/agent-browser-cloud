package io.browsercloud.application;

import static io.browsercloud.domain.agent.AgentModels.*;

import io.browsercloud.api.AgentTaskView.ExecutionMemoryEventView;
import io.browsercloud.api.AgentTaskView.TaskMemoryView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Append-only, data-minimized execution memory kept outside mutable Agent Task state. */
@Service
public class AgentTaskMemoryService {

  private static final int MAXIMUM_VISIBLE_EVENTS = 100;

  private final JdbcTemplate jdbc;
  private final AgentActionAttemptService actionAttempts;

  public AgentTaskMemoryService(JdbcTemplate jdbc, AgentActionAttemptService actionAttempts) {
    this.jdbc = jdbc;
    this.actionAttempts = actionAttempts;
  }

  public void recordResult(
      String tenantId,
      String sessionId,
      String taskId,
      String planIntentId,
      int stepOrdinal,
      PlanStep step,
      ToolExecutionResult result) {
    append(
        tenantId,
        sessionId,
        taskId,
        "result:" + step.stepId() + ":" + result.status(),
        "STEP_RESULT",
        planIntentId,
        stepOrdinal,
        step.stepId(),
        step.toolId(),
        actionAttempts.descriptorHash(step),
        result.status(),
        result.resultHash(),
        result.verification(),
        null,
        null,
        result.completedAt());
  }

  public void recordFailure(
      String tenantId,
      String sessionId,
      String taskId,
      String planIntentId,
      int stepOrdinal,
      PlanStep step,
      String reasonCode,
      Long stateVersion,
      Instant now) {
    append(
        tenantId,
        sessionId,
        taskId,
        "failure:" + step.stepId() + ":" + safeCode(reasonCode),
        "STEP_FAILURE",
        planIntentId,
        stepOrdinal,
        step.stepId(),
        step.toolId(),
        actionAttempts.descriptorHash(step),
        "FAILED",
        null,
        step.verification(),
        safeCode(reasonCode),
        stateVersion,
        now);
  }

  public void recordReplan(
      String tenantId,
      String sessionId,
      String taskId,
      String planIntentId,
      int replanCount,
      String reasonCode,
      Long stateVersion,
      Instant now) {
    append(
        tenantId,
        sessionId,
        taskId,
        "replan:" + replanCount,
        "REPLAN",
        planIntentId,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        safeCode(reasonCode),
        stateVersion,
        now);
  }

  public TaskMemoryView view(String taskId, String tenantId) {
    var events =
        jdbc.query(
            """
            SELECT memory_sequence, event_type, plan_intent_id, step_ordinal, step_id, tool_id,
                   semantic_key, status, result_hash, verification_code, reason_code,
                   state_version, created_at
              FROM (
                SELECT memory_sequence, event_type, plan_intent_id, step_ordinal, step_id, tool_id,
                       semantic_key, status, result_hash, verification_code, reason_code,
                       state_version, created_at
                  FROM agent_task_memory_events
                 WHERE task_id = ? AND tenant_id = ?
                 ORDER BY memory_sequence DESC
                 LIMIT ?
              ) AS recent_memory
             ORDER BY memory_sequence
            """,
            (result, row) ->
                new ExecutionMemoryEventView(
                    result.getInt("memory_sequence"),
                    result.getString("event_type"),
                    result.getString("plan_intent_id"),
                    nullableInteger(result, "step_ordinal"),
                    result.getString("step_id"),
                    nullableTool(result.getString("tool_id")),
                    result.getString("semantic_key"),
                    result.getString("status"),
                    result.getString("result_hash"),
                    result.getString("verification_code"),
                    result.getString("reason_code"),
                    nullableLong(result, "state_version"),
                    result.getTimestamp("created_at").toInstant()),
            taskId,
            tenantId,
            MAXIMUM_VISIBLE_EVENTS);
    var revision = events.isEmpty() ? 0 : events.getLast().sequence();
    return new TaskMemoryView(revision, List.copyOf(events));
  }

  private void append(
      String tenantId,
      String sessionId,
      String taskId,
      String eventKey,
      String eventType,
      String planIntentId,
      Integer stepOrdinal,
      String stepId,
      ToolId toolId,
      String semanticKey,
      String status,
      String resultHash,
      String verificationCode,
      String reasonCode,
      Long stateVersion,
      Instant now) {
    // Every execution path already owns this lock; retaining it here keeps future callers safe.
    jdbc.queryForObject(
        "SELECT task_id FROM agent_tasks WHERE task_id = ? AND tenant_id = ? FOR UPDATE",
        String.class,
        taskId,
        tenantId);
    jdbc.update(
        """
        INSERT INTO agent_task_memory_events (
          event_id, tenant_id, session_id, task_id, memory_sequence, event_key, event_type,
          plan_intent_id, step_ordinal, step_id, tool_id, semantic_key, status, result_hash,
          verification_code, reason_code, state_version, created_at
        )
        SELECT ?, ?, ?, ?, coalesce(max(memory_sequence), 0) + 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
               ?, ?, ?
          FROM agent_task_memory_events
         WHERE task_id = ?
        ON CONFLICT (task_id, event_key) DO NOTHING
        """,
        id(),
        tenantId,
        sessionId,
        taskId,
        eventKey,
        eventType,
        planIntentId,
        stepOrdinal,
        stepId,
        toolId == null ? null : toolId.name(),
        semanticKey,
        status,
        resultHash,
        verificationCode,
        reasonCode,
        stateVersion,
        Timestamp.from(now),
        taskId);
  }

  private static Integer nullableInteger(java.sql.ResultSet result, String column)
      throws java.sql.SQLException {
    var value = result.getInt(column);
    return result.wasNull() ? null : value;
  }

  private static Long nullableLong(java.sql.ResultSet result, String column)
      throws java.sql.SQLException {
    var value = result.getLong(column);
    return result.wasNull() ? null : value;
  }

  private static ToolId nullableTool(String value) {
    return value == null ? null : ToolId.valueOf(value);
  }

  private static String safeCode(String value) {
    return value != null && value.matches("^[A-Z][A-Z0-9_]{1,127}$")
        ? value
        : "TOOL_EXECUTION_FAILED";
  }

  private static String id() {
    return "atm_" + UUID.randomUUID().toString().replace("-", "");
  }
}
