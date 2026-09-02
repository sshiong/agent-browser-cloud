package io.browsercloud.application;

import static io.browsercloud.api.SessionDeletionModels.*;

import io.browsercloud.coordinator.OperationRepository;
import io.browsercloud.coordinator.exceptions.InvalidSessionStateException;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import io.browsercloud.domain.session.SessionState;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-authoritative, atomic Environment soft deletion. */
@Service
public class SessionDeletionApplicationService {

  private static final List<SessionState> DELETABLE_STATES =
      List.of(SessionState.CREATED, SessionState.TERMINATED);

  private final NamedParameterJdbcTemplate jdbc;
  private final OperationRepository operations;
  private final IdempotencyService idempotency;
  private final AuditApplicationService audit;

  public SessionDeletionApplicationService(
      NamedParameterJdbcTemplate jdbc,
      OperationRepository operations,
      IdempotencyService idempotency,
      AuditApplicationService audit) {
    this.jdbc = jdbc;
    this.operations = operations;
    this.idempotency = idempotency;
    this.audit = audit;
  }

  @Transactional
  public BatchDeleteSessionsResponse delete(
      String tenantId,
      String actorId,
      BatchDeleteSessionsRequest request,
      String idempotencyKey,
      String requestId) {
    var sessionIds = request.sessionIds().stream().distinct().sorted().toList();
    if (sessionIds.size() != request.sessionIds().size()) {
      throw new IllegalArgumentException("Session IDs must be unique");
    }
    var normalized = new BatchDeleteSessionsRequest(sessionIds);
    var candidateDeletionId = newId("sdel_");
    var deletionId =
        idempotency.claimSessionBatchDelete(
            tenantId, idempotencyKey, normalized, candidateDeletionId);
    if (!deletionId.equals(candidateDeletionId)) {
      return replay(tenantId, deletionId, sessionIds);
    }

    var parameters = new HashMap<String, Object>();
    parameters.put("tenantId", tenantId);
    parameters.put("sessionIds", sessionIds);
    var rows =
        jdbc.query(
            """
            SELECT id, state
              FROM sessions
             WHERE tenant_id = :tenantId
               AND id IN (:sessionIds)
               AND deleted_at IS NULL
             ORDER BY id
             FOR UPDATE
            """,
            parameters,
            (result, row) ->
                new SessionRow(
                    result.getString("id"), SessionState.valueOf(result.getString("state"))));
    if (rows.size() != sessionIds.size()) {
      throw new SessionNotFoundException(firstMissing(sessionIds, rows));
    }

    var activeOperations = operations.findActiveBySessionIds(sessionIds);
    for (var row : rows) {
      if (!DELETABLE_STATES.contains(row.state())
          || activeOperations.containsKey(row.sessionId())) {
        throw new InvalidSessionStateException(row.sessionId(), row.state(), "delete");
      }
    }

    var deletedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    parameters.put("actorId", actorId);
    parameters.put("deletionId", deletionId);
    parameters.put("deletedAt", Timestamp.from(deletedAt));
    // Route and ownership rows are live coordinator state, not retained evidence. Leaving them
    // behind would make a later tenant-route migration try to reconcile an intentionally hidden
    // Session. Release both under the same locked transaction before hiding the Session row.
    jdbc.update(
        """
        DELETE FROM coordinator_session_routes
         WHERE tenant_id = :tenantId
           AND session_id IN (:sessionIds)
        """,
        parameters);
    jdbc.update(
        """
        DELETE FROM coordinator_ownership
         WHERE session_id IN (:sessionIds)
        """,
        parameters);
    var updated =
        jdbc.update(
            """
            UPDATE sessions
               SET deleted_at = :deletedAt,
                   deleted_by = :actorId,
                   deletion_batch_id = :deletionId,
                   updated_at = :deletedAt
             WHERE tenant_id = :tenantId
               AND id IN (:sessionIds)
               AND deleted_at IS NULL
            """,
            parameters);
    if (updated != sessionIds.size()) {
      throw new IllegalStateException("Session deletion lost its locked row set");
    }

    for (var sessionId : sessionIds) {
      audit.append(
          new AuditApplicationService.AuditRecord(
              tenantId,
              sessionId,
              "SESSION_METADATA",
              "USER",
              actorId,
              "SESSION",
              sessionId,
              "DELETE",
              "COMMITTED",
              Map.of("deletionId", deletionId, "batchSize", sessionIds.size(), "softDelete", true),
              requestId + ":" + sessionId));
    }
    return new BatchDeleteSessionsResponse(deletionId, sessionIds.size(), sessionIds, deletedAt);
  }

  private BatchDeleteSessionsResponse replay(
      String tenantId, String deletionId, List<String> sessionIds) {
    var parameters =
        Map.<String, Object>of(
            "tenantId", tenantId, "sessionIds", sessionIds, "deletionId", deletionId);
    var rows =
        jdbc.query(
            """
            SELECT id, deleted_at
              FROM sessions
             WHERE tenant_id = :tenantId
               AND id IN (:sessionIds)
               AND deletion_batch_id = :deletionId
             ORDER BY id
            """,
            parameters,
            (result, row) ->
                new DeletedRow(
                    result.getString("id"), result.getTimestamp("deleted_at").toInstant()));
    if (rows.size() != sessionIds.size()) {
      throw new IllegalStateException("Idempotent Session deletion result is incomplete");
    }
    return new BatchDeleteSessionsResponse(
        deletionId,
        rows.size(),
        rows.stream().map(DeletedRow::sessionId).toList(),
        rows.get(0).deletedAt());
  }

  private static String firstMissing(List<String> requested, List<SessionRow> rows) {
    var found =
        rows.stream().map(SessionRow::sessionId).collect(java.util.stream.Collectors.toSet());
    return requested.stream()
        .filter(id -> !found.contains(id))
        .findFirst()
        .orElse(requested.get(0));
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private record SessionRow(String sessionId, SessionState state) {}

  private record DeletedRow(String sessionId, Instant deletedAt) {}
}
