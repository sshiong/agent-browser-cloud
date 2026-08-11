package io.browsercloud.persistence;

import io.browsercloud.api.AgentTaskSummaryListResponse;
import io.browsercloud.api.AgentTaskSummaryView;
import io.browsercloud.domain.agent.AgentPolicy;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Fixed-query-count PostgreSQL projection that never materializes task plan/evidence JSON. */
@Repository
public class AgentTaskSummaryQueryRepository {

  private static final Base64.Encoder CURSOR_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder CURSOR_DECODER = Base64.getUrlDecoder();
  private final NamedParameterJdbcTemplate jdbc;

  public AgentTaskSummaryQueryRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public AgentTaskSummaryListResponse list(String tenantId, int requestedLimit, String cursor) {
    int limit = Math.max(1, Math.min(requestedLimit, 50));
    CursorPosition position = decodeCursor(cursor);
    var parameters =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("fetchLimit", limit + 1);
    String cursorPredicate = "";
    if (position != null) {
      parameters
          .addValue("cursorCreatedAt", Timestamp.from(position.createdAt()))
          .addValue("cursorTaskId", position.taskId());
      cursorPredicate = " AND (task.created_at, task.task_id) < (:cursorCreatedAt, :cursorTaskId) ";
    }

    List<AgentTaskSummaryView> fetched =
        jdbc.query(
            """
            SELECT task.task_id,
                   task.session_id,
                   task.goal,
                   task.state,
                   task.risk_class,
                   task.intent_decision,
                   task.blocked_reason,
                   task.agent_policy,
                   task.current_step,
                   jsonb_array_length(COALESCE(task.plan->'steps', '[]'::jsonb)) AS total_steps,
                   jsonb_array_length(task.security_events) AS security_event_count,
                   task.execution_wait_reason,
                   task.execution_wait_since,
                   task.created_at,
                   task.updated_at
              FROM agent_tasks task
             WHERE task.tenant_id = :tenantId
            """
                + cursorPredicate
                + """
             ORDER BY task.created_at DESC, task.task_id DESC
             LIMIT :fetchLimit
            """,
            parameters,
            (resultSet, rowNumber) ->
                new AgentTaskSummaryView(
                    resultSet.getString("task_id"),
                    resultSet.getString("session_id"),
                    resultSet.getString("goal"),
                    resultSet.getString("state"),
                    resultSet.getString("risk_class"),
                    resultSet.getString("intent_decision"),
                    resultSet.getString("blocked_reason"),
                    AgentPolicy.valueOf(resultSet.getString("agent_policy")),
                    resultSet.getInt("current_step"),
                    resultSet.getInt("total_steps"),
                    resultSet.getInt("security_event_count"),
                    resultSet.getString("execution_wait_reason"),
                    resultSet.getTimestamp("execution_wait_since") == null
                        ? null
                        : resultSet.getTimestamp("execution_wait_since").toInstant(),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant()));

    var aggregate =
        jdbc.queryForMap(
            """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE state IN ('PLANNED', 'QUEUED', 'AWAITING_REVIEW')) AS planned,
                   count(*) FILTER (WHERE state = 'COMPLETED') AS completed,
                   count(*) FILTER (WHERE state = 'BLOCKED') AS blocked
              FROM agent_tasks
             WHERE tenant_id = :tenantId
            """,
            Map.of("tenantId", tenantId));

    boolean hasMore = fetched.size() > limit;
    var items = new ArrayList<>(fetched.subList(0, Math.min(limit, fetched.size())));
    String nextCursor =
        hasMore && !items.isEmpty()
            ? encodeCursor(items.getLast().createdAt(), items.getLast().taskId())
            : null;
    return new AgentTaskSummaryListResponse(
        items,
        new AgentTaskSummaryListResponse.Metrics(
            number(aggregate, "planned"),
            number(aggregate, "completed"),
            number(aggregate, "blocked")),
        number(aggregate, "total"),
        limit,
        nextCursor,
        hasMore);
  }

  static String encodeCursor(Instant createdAt, String taskId) {
    String value = createdAt.getEpochSecond() + ":" + createdAt.getNano() + ":" + taskId;
    return CURSOR_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  static CursorPosition decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String decoded = new String(CURSOR_DECODER.decode(cursor), StandardCharsets.UTF_8);
      String[] parts = decoded.split(":", 3);
      if (parts.length != 3 || !parts[2].matches("^agt_[a-zA-Z0-9]{16,}$")) {
        throw new IllegalArgumentException("invalid task cursor");
      }
      long seconds = Long.parseLong(parts[0]);
      int nanos = Integer.parseInt(parts[1]);
      if (nanos < 0 || nanos > 999_999_999) {
        throw new IllegalArgumentException("invalid task cursor");
      }
      return new CursorPosition(Instant.ofEpochSecond(seconds, nanos), parts[2]);
    } catch (RuntimeException exception) {
      throw new InvalidAgentTaskSummaryCursorException();
    }
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  record CursorPosition(Instant createdAt, String taskId) {}

  public static final class InvalidAgentTaskSummaryCursorException extends RuntimeException {}
}
