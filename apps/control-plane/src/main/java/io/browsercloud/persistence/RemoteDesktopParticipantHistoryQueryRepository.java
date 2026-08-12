package io.browsercloud.persistence;

import static io.browsercloud.api.RemoteDesktopParticipantHistoryModels.*;
import static io.browsercloud.api.RemoteDesktopParticipantModels.*;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stable keyset history projection; never exposes a ticket nonce or credential. */
@Repository
public class RemoteDesktopParticipantHistoryQueryRepository {
  private static final Base64.Encoder CURSOR_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder CURSOR_DECODER = Base64.getUrlDecoder();
  private final NamedParameterJdbcTemplate jdbc;

  public RemoteDesktopParticipantHistoryQueryRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public RemoteDesktopParticipantHistoryPage list(
      String tenantId, String sessionId, int requestedLimit, String cursor) {
    int limit = Math.max(1, Math.min(requestedLimit, 50));
    CursorPosition position = decodeCursor(sessionId, cursor);
    var parameters =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("sessionId", sessionId)
            .addValue("fetchLimit", limit + 1);
    String cursorPredicate = "";
    if (position != null) {
      parameters
          .addValue("cursorObservedAt", Timestamp.from(position.observedAt()))
          .addValue("cursorConnectionId", position.connectionId());
      cursorPredicate =
          " AND (participant.observed_at, participant.connection_id)"
              + " < (:cursorObservedAt, :cursorConnectionId) ";
    }

    var fetched =
        jdbc.query(
            """
            SELECT participant.connection_id,
                   participant.session_id,
                   participant.context_epoch,
                   participant.actor_id,
                   participant.access_mode,
                   participant.view_only,
                   participant.state,
                   participant.reason,
                   participant.connected_at,
                   participant.disconnected_at,
                   participant.revoked_by,
                   participant.revoke_requested_at,
                   participant.observed_at,
                   participant.updated_at,
                   participant.forwarded_bytes,
                   participant.quota_wait_millis,
                   participant.throttled_batches,
                   participant.egress_cost_usd,
                   participant.unpriced_forwarded_bytes,
                   participant.last_cost_pricing_version,
                   participant.last_egress_gib_usd
              FROM remote_desktop_participants participant
             WHERE participant.tenant_id = :tenantId
               AND participant.session_id = :sessionId
               AND participant.state IN ('REVOKED', 'DISCONNECTED')
            """
                + cursorPredicate
                + """
             ORDER BY participant.observed_at DESC, participant.connection_id DESC
             LIMIT :fetchLimit
            """,
            parameters,
            (resultSet, rowNumber) ->
                new RemoteDesktopParticipantView(
                    resultSet.getString("connection_id"),
                    resultSet.getString("session_id"),
                    resultSet.getLong("context_epoch"),
                    resultSet.getString("actor_id"),
                    resultSet.getString("access_mode"),
                    resultSet.getObject("view_only", Boolean.class),
                    resultSet.getString("state"),
                    resultSet.getString("reason"),
                    instant(resultSet.getTimestamp("connected_at")),
                    instant(resultSet.getTimestamp("disconnected_at")),
                    resultSet.getString("revoked_by"),
                    instant(resultSet.getTimestamp("revoke_requested_at")),
                    resultSet.getTimestamp("observed_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant(),
                    resultSet.getLong("forwarded_bytes"),
                    resultSet.getLong("quota_wait_millis"),
                    resultSet.getLong("throttled_batches"),
                    resultSet.getBigDecimal("egress_cost_usd"),
                    resultSet.getLong("unpriced_forwarded_bytes"),
                    resultSet.getString("last_cost_pricing_version"),
                    resultSet.getBigDecimal("last_egress_gib_usd")));
    long total =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM remote_desktop_participants
             WHERE tenant_id = :tenantId
               AND session_id = :sessionId
               AND state IN ('REVOKED', 'DISCONNECTED')
            """,
            parameters,
            Long.class);
    boolean hasMore = fetched.size() > limit;
    var items = new ArrayList<>(fetched.subList(0, Math.min(limit, fetched.size())));
    String nextCursor =
        hasMore && !items.isEmpty()
            ? encodeCursor(sessionId, items.getLast().observedAt(), items.getLast().connectionId())
            : null;
    return new RemoteDesktopParticipantHistoryPage(items, total, limit, nextCursor, hasMore);
  }

  @Transactional
  public int purgeTerminalBefore(Instant cutoff, int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, 10_000));
    return jdbc.update(
        """
        DELETE FROM remote_desktop_participants
         WHERE connection_id IN (
           SELECT connection_id
             FROM remote_desktop_participants
            WHERE state IN ('REVOKED', 'DISCONNECTED')
              AND updated_at < :cutoff
            ORDER BY updated_at, connection_id
            LIMIT :limit
         )
        """,
        Map.of("cutoff", Timestamp.from(cutoff), "limit", limit));
  }

  static String encodeCursor(String sessionId, Instant observedAt, String connectionId) {
    String value =
        sessionId
            + ":"
            + observedAt.getEpochSecond()
            + ":"
            + observedAt.getNano()
            + ":"
            + connectionId;
    return CURSOR_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  static CursorPosition decodeCursor(String sessionId, String cursor) {
    if (cursor == null || cursor.isBlank()) return null;
    try {
      String decoded = new String(CURSOR_DECODER.decode(cursor), StandardCharsets.UTF_8);
      String[] parts = decoded.split(":", 4);
      if (parts.length != 4
          || !sessionId.equals(parts[0])
          || !parts[3].matches("^rdc_[a-zA-Z0-9]{20}$")) {
        throw new IllegalArgumentException("invalid participant history cursor");
      }
      int nanos = Integer.parseInt(parts[2]);
      if (nanos < 0 || nanos > 999_999_999) {
        throw new IllegalArgumentException("invalid participant history cursor");
      }
      return new CursorPosition(Instant.ofEpochSecond(Long.parseLong(parts[1]), nanos), parts[3]);
    } catch (RuntimeException exception) {
      throw new InvalidRemoteDesktopParticipantCursorException();
    }
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  record CursorPosition(Instant observedAt, String connectionId) {}

  public static final class InvalidRemoteDesktopParticipantCursorException
      extends RuntimeException {}
}
