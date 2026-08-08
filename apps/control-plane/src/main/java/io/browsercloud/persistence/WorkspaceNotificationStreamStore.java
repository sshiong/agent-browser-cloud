package io.browsercloud.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the tenant-local immutable Audit sequence used by the notification SSE feed. */
@Repository
public class WorkspaceNotificationStreamStore {
  private final JdbcTemplate jdbc;

  public WorkspaceNotificationStreamStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long latestSequence(String tenantId) {
    var value =
        jdbc.queryForObject(
            """
            SELECT COALESCE(MAX(audit_sequence_no), 0)
            FROM workspace_notifications
            WHERE tenant_id = ? AND expires_at > CURRENT_TIMESTAMP
            """,
            Long.class,
            tenantId);
    return value == null ? 0 : value;
  }

  public List<DurableNotificationChange> readAfter(String tenantId, long afterSequence, int limit) {
    return jdbc.query(
        """
        SELECT audit_sequence_no, created_at
        FROM workspace_notifications
        WHERE tenant_id = ?
          AND expires_at > CURRENT_TIMESTAMP
          AND audit_sequence_no > ?
        ORDER BY audit_sequence_no
        LIMIT ?
        """,
        WorkspaceNotificationStreamStore::mapChange,
        tenantId,
        afterSequence,
        Math.max(1, Math.min(limit, 500)));
  }

  private static DurableNotificationChange mapChange(ResultSet result, int row)
      throws SQLException {
    return new DurableNotificationChange(
        result.getLong("audit_sequence_no"),
        result.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  public record DurableNotificationChange(long sequence, Instant occurredAt) {}
}
