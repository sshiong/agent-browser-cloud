package io.browsercloud.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the immutable per-tenant audit chain as a resumable invalidation cursor.
 *
 * <p>Unlike the notification projection, this feed carries every audited row, so a client that
 * follows the cursor can refetch the full audit ledger without the "missed event never refreshes"
 * risk of a high-signal-only projection. Rows written before V010 have a NULL sequence: they
 * predate the tamper-evident chain and can never advance a live cursor.
 */
@Repository
public class AuditEventStreamStore {
  private final JdbcTemplate jdbc;

  public AuditEventStreamStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long latestSequence(String tenantId) {
    var value =
        jdbc.queryForObject(
            """
            SELECT COALESCE(max(sequence_no), 0)
            FROM audit_events
            WHERE tenant_id = ? AND sequence_no IS NOT NULL
            """,
            Long.class,
            tenantId);
    return value == null ? 0 : value;
  }

  public List<DurableAuditChange> readAfter(String tenantId, long afterSequence, int limit) {
    return jdbc.query(
        """
        SELECT sequence_no, created_at
        FROM audit_events
        WHERE tenant_id = ? AND sequence_no IS NOT NULL AND sequence_no > ?
        ORDER BY sequence_no
        LIMIT ?
        """,
        AuditEventStreamStore::mapChange,
        tenantId,
        afterSequence,
        Math.max(1, Math.min(limit, 500)));
  }

  private static DurableAuditChange mapChange(ResultSet result, int row) throws SQLException {
    return new DurableAuditChange(
        result.getLong("sequence_no"),
        result.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  /** Payload-free audit cursor advance; the client refetches its authorized projection. */
  public record DurableAuditChange(long sequence, Instant occurredAt) {}
}
