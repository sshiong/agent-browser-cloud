package io.browsercloud.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the durable resource change feed shared by telemetry samples and adjustment timeline
 * events.
 */
@Repository
public class SessionResourceStreamStore {

  private final JdbcTemplate jdbc;

  public SessionResourceStreamStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long latestSequence(String tenantId, String sessionId) {
    var value =
        jdbc.queryForObject(
            """
            SELECT COALESCE(
                (
                    SELECT last_sequence
                    FROM session_resource_stream_cursors
                    WHERE tenant_id = ? AND session_id = ?
                ),
                0
            )
            """,
            Long.class,
            tenantId,
            sessionId);
    return value == null ? 0 : value;
  }

  public List<DurableResourceChange> readAfter(
      String tenantId, String sessionId, long afterSequence, int limit) {
    return jdbc.query(
        """
        SELECT stream_sequence, change_type, entity_id, occurred_at
        FROM (
            SELECT
                stream_sequence,
                'RESOURCE_SAMPLE' AS change_type,
                sample_id AS entity_id,
                observed_at AS occurred_at
            FROM session_resource_samples
            WHERE tenant_id = ? AND session_id = ? AND stream_sequence > ?
            UNION ALL
            SELECT
                stream_sequence,
                'RESOURCE_EVENT' AS change_type,
                event_id AS entity_id,
                occurred_at
            FROM session_resource_events
            WHERE tenant_id = ? AND session_id = ? AND stream_sequence > ?
        ) durable_changes
        ORDER BY stream_sequence
        LIMIT ?
        """,
        SessionResourceStreamStore::mapChange,
        tenantId,
        sessionId,
        afterSequence,
        tenantId,
        sessionId,
        afterSequence,
        Math.max(1, Math.min(limit, 500)));
  }

  private static DurableResourceChange mapChange(ResultSet result, int row) throws SQLException {
    return new DurableResourceChange(
        result.getLong("stream_sequence"),
        result.getString("change_type"),
        result.getString("entity_id"),
        result.getObject("occurred_at", OffsetDateTime.class).toInstant());
  }

  public record DurableResourceChange(
      long sequence, String changeType, String entityId, Instant occurredAt) {}
}
