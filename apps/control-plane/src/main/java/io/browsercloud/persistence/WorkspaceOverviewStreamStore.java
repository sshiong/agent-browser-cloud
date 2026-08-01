package io.browsercloud.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the durable tenant/global invalidation feed for Workspace Overview. */
@Repository
public class WorkspaceOverviewStreamStore {
  private final JdbcTemplate jdbc;

  public WorkspaceOverviewStreamStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long latestSequence(String tenantId, boolean includePlatformEvents) {
    var value =
        jdbc.queryForObject(
            """
            SELECT COALESCE(max(stream_sequence), 0)
            FROM workspace_overview_events
            WHERE tenant_id = ? OR (? AND tenant_id IS NULL)
            """,
            Long.class,
            tenantId,
            includePlatformEvents);
    return value == null ? 0 : value;
  }

  public List<DurableWorkspaceChange> readAfter(
      String tenantId, boolean includePlatformEvents, long afterSequence, int limit) {
    return jdbc.query(
        """
        SELECT stream_sequence, change_type, occurred_at
        FROM workspace_overview_events
        WHERE (tenant_id = ? OR (? AND tenant_id IS NULL)) AND stream_sequence > ?
        ORDER BY stream_sequence
        LIMIT ?
        """,
        WorkspaceOverviewStreamStore::mapChange,
        tenantId,
        includePlatformEvents,
        afterSequence,
        Math.max(1, Math.min(limit, 500)));
  }

  private static DurableWorkspaceChange mapChange(ResultSet result, int row) throws SQLException {
    return new DurableWorkspaceChange(
        result.getLong("stream_sequence"),
        result.getString("change_type"),
        result.getObject("occurred_at", OffsetDateTime.class).toInstant());
  }

  public record DurableWorkspaceChange(long sequence, String changeType, Instant occurredAt) {}
}
