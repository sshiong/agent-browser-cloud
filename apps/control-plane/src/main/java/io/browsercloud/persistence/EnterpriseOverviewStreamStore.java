package io.browsercloud.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads the complete tenant/global invalidation projection for Enterprise Overview. */
@Repository
public class EnterpriseOverviewStreamStore {
  private final JdbcTemplate jdbc;

  public EnterpriseOverviewStreamStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long latestSequence(String tenantId) {
    var value =
        jdbc.queryForObject(
            """
            SELECT COALESCE(max(stream_sequence), 0)
            FROM enterprise_overview_events
            WHERE tenant_id = ? OR tenant_id IS NULL
            """,
            Long.class,
            tenantId);
    return value == null ? 0 : value;
  }

  public List<DurableEnterpriseOverviewChange> readAfter(
      String tenantId, long afterSequence, int limit) {
    return jdbc.query(
        """
        SELECT stream_sequence, change_type, occurred_at
        FROM enterprise_overview_events
        WHERE (tenant_id = ? OR tenant_id IS NULL) AND stream_sequence > ?
        ORDER BY stream_sequence
        LIMIT ?
        """,
        EnterpriseOverviewStreamStore::mapChange,
        tenantId,
        afterSequence,
        Math.max(1, Math.min(limit, 500)));
  }

  @Transactional
  public int publishDueInvalidations(int limit) {
    return jdbc.update(
        """
        WITH due AS (
          SELECT invalidation_key
          FROM enterprise_overview_scheduled_invalidations
          WHERE due_at <= now()
          ORDER BY due_at, invalidation_key
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        ), removed AS (
          DELETE FROM enterprise_overview_scheduled_invalidations scheduled
          USING due
          WHERE scheduled.invalidation_key = due.invalidation_key
          RETURNING scheduled.tenant_id, scheduled.change_type, scheduled.due_at
        )
        INSERT INTO enterprise_overview_events(tenant_id, change_type, occurred_at)
        SELECT tenant_id, change_type, due_at FROM removed
        """,
        Math.max(1, Math.min(limit, 1_000)));
  }

  private static DurableEnterpriseOverviewChange mapChange(ResultSet result, int row)
      throws SQLException {
    return new DurableEnterpriseOverviewChange(
        result.getLong("stream_sequence"),
        result.getString("change_type"),
        result.getObject("occurred_at", OffsetDateTime.class).toInstant());
  }

  public record DurableEnterpriseOverviewChange(
      long sequence, String changeType, Instant occurredAt) {}
}
