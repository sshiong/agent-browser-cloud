package io.browsercloud.application;

import static io.browsercloud.api.WorkspaceNotificationModels.NotificationCategory;
import static io.browsercloud.api.WorkspaceNotificationModels.NotificationSeverity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded PostgreSQL reads and monotonic per-actor cursor updates for workspace notifications. */
@Service
public class WorkspaceNotificationStore {

  private final JdbcTemplate jdbc;

  public WorkspaceNotificationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public NotificationSnapshot snapshot(
      String tenantId, String actorId, int limit, Long beforeSequence, Instant now) {
    long readSequence = readCursor(tenantId, actorId).lastReadSequence();
    long headSequence = headSequence(tenantId, now);
    long exclusiveUpperBound =
        beforeSequence == null ? Long.MAX_VALUE : Math.max(1L, beforeSequence);
    var items =
        jdbc.query(
            """
            SELECT notification_id, audit_sequence_no, session_id, event_type,
                   category, severity, resource_type, resource_id, action, result,
                   request_id, created_at
            FROM workspace_notifications
            WHERE tenant_id = ?
              AND expires_at > ?
              AND audit_sequence_no < ?
            ORDER BY audit_sequence_no DESC
            LIMIT ?
            """,
            (result, rowNumber) ->
                new StoredNotification(
                    result.getString("notification_id"),
                    result.getLong("audit_sequence_no"),
                    result.getString("session_id"),
                    result.getString("event_type"),
                    NotificationCategory.valueOf(result.getString("category")),
                    NotificationSeverity.valueOf(result.getString("severity")),
                    result.getString("resource_type"),
                    result.getString("resource_id"),
                    result.getString("action"),
                    result.getString("result"),
                    result.getString("request_id"),
                    result.getTimestamp("created_at").toInstant()),
            tenantId,
            Timestamp.from(now),
            exclusiveUpperBound,
            limit + 1);
    boolean truncated = items.size() > limit;
    var page = truncated ? List.copyOf(items.subList(0, limit)) : List.copyOf(items);
    Long nextBeforeSequence = truncated && !page.isEmpty() ? page.getLast().sequence() : null;
    return new NotificationSnapshot(
        page,
        unreadCount(tenantId, readSequence, now),
        readSequence,
        headSequence,
        nextBeforeSequence);
  }

  @Transactional
  public StoredReadState markRead(
      String tenantId, String actorId, long requestedSequence, Instant now) {
    long headSequence = headSequence(tenantId, now);
    if (requestedSequence < 0 || requestedSequence > headSequence) {
      throw new IllegalArgumentException(
          "Notification read cursor must reference the current notification feed");
    }
    jdbc.update(
        """
        INSERT INTO workspace_notification_read_cursors(
            tenant_id, actor_id, last_read_sequence, updated_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (tenant_id, actor_id)
        DO UPDATE SET
            last_read_sequence = GREATEST(
              workspace_notification_read_cursors.last_read_sequence,
              EXCLUDED.last_read_sequence
            ),
            updated_at = CASE
              WHEN EXCLUDED.last_read_sequence >
                   workspace_notification_read_cursors.last_read_sequence
              THEN EXCLUDED.updated_at
              ELSE workspace_notification_read_cursors.updated_at
            END
        """,
        tenantId,
        actorId,
        requestedSequence,
        Timestamp.from(now));
    var committed = readCursor(tenantId, actorId);
    return new StoredReadState(
        committed.lastReadSequence(),
        unreadCount(tenantId, committed.lastReadSequence(), now),
        committed.updatedAt());
  }

  @Scheduled(cron = "${notifications.cleanup-cron:0 17 * * * *}")
  @Transactional
  public void deleteExpired() {
    jdbc.update(
        """
        DELETE FROM workspace_notifications
        WHERE ctid IN (
          SELECT ctid
          FROM workspace_notifications
          WHERE expires_at <= ?
          ORDER BY expires_at
          LIMIT 5000
        )
        """,
        Timestamp.from(Instant.now()));
  }

  private CursorState readCursor(String tenantId, String actorId) {
    var values =
        jdbc.query(
            """
            SELECT last_read_sequence, updated_at
            FROM workspace_notification_read_cursors
            WHERE tenant_id = ? AND actor_id = ?
            """,
            (result, rowNumber) ->
                new CursorState(
                    result.getLong("last_read_sequence"),
                    result.getTimestamp("updated_at").toInstant()),
            tenantId,
            actorId);
    return values.isEmpty() ? new CursorState(0, Instant.EPOCH) : values.getFirst();
  }

  private long headSequence(String tenantId, Instant now) {
    Long value =
        jdbc.queryForObject(
            """
            SELECT COALESCE(MAX(audit_sequence_no), 0)
            FROM workspace_notifications
            WHERE tenant_id = ? AND expires_at > ?
            """,
            Long.class,
            tenantId,
            Timestamp.from(now));
    return value == null ? 0 : value;
  }

  private long unreadCount(String tenantId, long readSequence, Instant now) {
    Long value =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM workspace_notifications
            WHERE tenant_id = ?
              AND expires_at > ?
              AND audit_sequence_no > ?
            """,
            Long.class,
            tenantId,
            Timestamp.from(now),
            readSequence);
    return value == null ? 0 : value;
  }

  public record StoredNotification(
      String notificationId,
      long sequence,
      String sessionId,
      String eventType,
      NotificationCategory category,
      NotificationSeverity severity,
      String resourceType,
      String resourceId,
      String action,
      String result,
      String requestId,
      Instant occurredAt) {}

  public record NotificationSnapshot(
      List<StoredNotification> items,
      long unreadCount,
      long lastReadSequence,
      long headSequence,
      Long nextBeforeSequence) {}

  public record StoredReadState(long lastReadSequence, long unreadCount, Instant updatedAt) {}

  private record CursorState(long lastReadSequence, Instant updatedAt) {}
}
