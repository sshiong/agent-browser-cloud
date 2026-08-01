package io.browsercloud.infrastructure;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL lease and fencing store for metadata batch items. */
@Service
public class WorkspaceMetadataBatchClaimStore {

  private static final int CLAIM_BATCH_SIZE = 25;
  private final NamedParameterJdbcTemplate jdbc;
  private final String workerId;
  private final Duration claimLease;

  public WorkspaceMetadataBatchClaimStore(
      NamedParameterJdbcTemplate jdbc,
      @Value("${coordinator.instance-id:${random.uuid}}") String coordinatorId,
      @Value("${workspace.metadata-batch.claim-lease-seconds:30}") long claimLeaseSeconds) {
    if (coordinatorId == null || coordinatorId.isBlank()) {
      throw new IllegalArgumentException("coordinator.instance-id is required");
    }
    if (claimLeaseSeconds < 10 || claimLeaseSeconds > 300) {
      throw new IllegalArgumentException(
          "workspace.metadata-batch.claim-lease-seconds must be between 10 and 300");
    }
    this.jdbc = jdbc;
    this.workerId = coordinatorId + ":workspace-metadata";
    this.claimLease = Duration.ofSeconds(claimLeaseSeconds);
  }

  @Transactional
  public ClaimedOperation claimOperation(NewOperation operation) {
    jdbc.update(
        """
        INSERT INTO workspace_metadata_batch_operations(
            batch_operation_id, tenant_id, actor_id, action, selector,
            target_group_id, target_tag_ids, reason, request_hash, idempotency_key,
            deadline_at, created_at, updated_at
        ) VALUES (
            :batchOperationId, :tenantId, :actorId, :action, CAST(:selector AS jsonb),
            :targetGroupId, CAST(:targetTagIds AS jsonb), :reason, :requestHash,
            :idempotencyKey, :deadlineAt, :now, :now
        )
        ON CONFLICT(tenant_id, idempotency_key) DO NOTHING
        """,
        Map.ofEntries(
            Map.entry("batchOperationId", operation.batchOperationId()),
            Map.entry("tenantId", operation.tenantId()),
            Map.entry("actorId", operation.actorId()),
            Map.entry("action", operation.action()),
            Map.entry("selector", operation.selector()),
            Map.entry("targetGroupId", nullable(operation.targetGroupId())),
            Map.entry("targetTagIds", operation.targetTagIds()),
            Map.entry("reason", operation.reason()),
            Map.entry("requestHash", operation.requestHash()),
            Map.entry("idempotencyKey", operation.idempotencyKey()),
            Map.entry("deadlineAt", Timestamp.from(operation.deadlineAt())),
            Map.entry("now", Timestamp.from(operation.now()))));
    return jdbc
        .query(
            """
            SELECT batch_operation_id, request_hash
              FROM workspace_metadata_batch_operations
             WHERE tenant_id = :tenantId
               AND idempotency_key = :idempotencyKey
            """,
            Map.of("tenantId", operation.tenantId(), "idempotencyKey", operation.idempotencyKey()),
            (resultSet, rowNumber) ->
                new ClaimedOperation(
                    resultSet.getString("batch_operation_id"), resultSet.getString("request_hash")))
        .stream()
        .findFirst()
        .orElseThrow(
            () ->
                new WorkspaceMetadataBatchClaimRejectedException(
                    "METADATA_BATCH_IDEMPOTENCY_CLAIM_LOST"));
  }

  @Transactional
  public List<String> claimReady(Instant now) {
    var claimed =
        jdbc.query(
            """
        WITH candidates AS (
          SELECT item.batch_item_id
            FROM workspace_metadata_batch_operation_items item
            JOIN workspace_metadata_batch_operations operation
              ON operation.batch_operation_id = item.batch_operation_id
             AND operation.tenant_id = item.tenant_id
           WHERE operation.deadline_at > :now
             AND item.attempt < 3
             AND (
               (
                 item.state = 'ACCEPTED'
                 AND operation.cancellation_requested_at IS NULL
                 AND item.next_attempt_at <= :now
               )
               OR
               (
                 item.state = 'EXECUTING'
                 AND item.claim_lease_until < :now
               )
             )
           ORDER BY item.next_attempt_at, item.created_at, item.batch_item_id
           LIMIT :batchSize
           FOR UPDATE OF item SKIP LOCKED
        )
        UPDATE workspace_metadata_batch_operation_items item
           SET state = 'EXECUTING',
               attempt = item.attempt + 1,
               claim_owner = :workerId,
               claim_lease_until = :leaseUntil,
               started_at = COALESCE(item.started_at, :now)
          FROM candidates
         WHERE item.batch_item_id = candidates.batch_item_id
        RETURNING item.batch_item_id
        """,
            Map.of(
                "now",
                Timestamp.from(now),
                "leaseUntil",
                Timestamp.from(now.plus(claimLease)),
                "workerId",
                workerId,
                "batchSize",
                CLAIM_BATCH_SIZE),
            (resultSet, rowNumber) -> resultSet.getString("batch_item_id"));
    if (!claimed.isEmpty()) {
      jdbc.update(
          """
          UPDATE workspace_metadata_batch_operations operation
             SET updated_at = :now
           WHERE EXISTS (
             SELECT 1
               FROM workspace_metadata_batch_operation_items item
              WHERE item.batch_operation_id = operation.batch_operation_id
                AND item.batch_item_id IN (:batchItemIds)
           )
          """,
          Map.of("now", Timestamp.from(now), "batchItemIds", claimed));
    }
    return claimed;
  }

  @Transactional
  public ClaimedItem requireClaimedForUpdate(String batchItemId) {
    var rows =
        jdbc.query(
            """
            SELECT item.batch_item_id, item.batch_operation_id, item.tenant_id,
                   item.session_id, item.attempt, item.state, item.claim_owner
              FROM workspace_metadata_batch_operation_items item
             WHERE item.batch_item_id = :batchItemId
             FOR UPDATE
            """,
            Map.of("batchItemId", batchItemId),
            (resultSet, rowNumber) ->
                new ClaimedItem(
                    resultSet.getString("batch_item_id"),
                    resultSet.getString("batch_operation_id"),
                    resultSet.getString("tenant_id"),
                    resultSet.getString("session_id"),
                    resultSet.getInt("attempt"),
                    resultSet.getString("state"),
                    resultSet.getString("claim_owner")));
    if (rows.isEmpty()) {
      throw new WorkspaceMetadataBatchClaimRejectedException("METADATA_BATCH_ITEM_NOT_FOUND");
    }
    var item = rows.getFirst();
    if (!"EXECUTING".equals(item.state()) || !workerId.equals(item.claimOwner())) {
      throw new WorkspaceMetadataBatchClaimRejectedException("METADATA_BATCH_ITEM_CLAIM_LOST");
    }
    return item;
  }

  @Transactional
  public void commit(String batchItemId, String batchOperationId, Instant now) {
    var changed =
        jdbc.update(
            """
            UPDATE workspace_metadata_batch_operation_items
               SET state = 'SUCCEEDED',
                   failure_code = NULL,
                   claim_owner = NULL,
                   claim_lease_until = NULL,
                   completed_at = :now
             WHERE batch_item_id = :batchItemId
               AND state = 'EXECUTING'
               AND claim_owner = :workerId
            """,
            Map.of("batchItemId", batchItemId, "workerId", workerId, "now", Timestamp.from(now)));
    if (changed != 1) {
      throw new WorkspaceMetadataBatchClaimRejectedException("METADATA_BATCH_ITEM_COMMIT_FENCED");
    }
    touch(batchOperationId, now);
  }

  @Transactional
  public void retryOrFail(
      String batchItemId, String failureCode, Instant now, int maximumAttempts) {
    var state =
        jdbc.queryForList(
            """
            SELECT batch_operation_id, attempt
              FROM workspace_metadata_batch_operation_items
             WHERE batch_item_id = :batchItemId
               AND state = 'EXECUTING'
               AND claim_owner = :workerId
            """,
            Map.of("batchItemId", batchItemId, "workerId", workerId));
    if (state.isEmpty()) {
      return;
    }
    var batchOperationId = String.valueOf(state.getFirst().get("batch_operation_id"));
    var attempt = ((Number) state.getFirst().get("attempt")).intValue();
    if (attempt < maximumAttempts) {
      jdbc.update(
          """
          UPDATE workspace_metadata_batch_operation_items
             SET state = 'ACCEPTED',
                 failure_code = NULL,
                 claim_owner = NULL,
                 claim_lease_until = NULL,
                 next_attempt_at = :nextAttempt
           WHERE batch_item_id = :batchItemId
             AND state = 'EXECUTING'
             AND claim_owner = :workerId
          """,
          Map.of(
              "batchItemId",
              batchItemId,
              "workerId",
              workerId,
              "nextAttempt",
              Timestamp.from(now.plusSeconds(1))));
    } else {
      jdbc.update(
          """
          UPDATE workspace_metadata_batch_operation_items
             SET state = 'FAILED',
                 failure_code = :failureCode,
                 claim_owner = NULL,
                 claim_lease_until = NULL,
                 completed_at = :now
           WHERE batch_item_id = :batchItemId
             AND state = 'EXECUTING'
             AND claim_owner = :workerId
          """,
          Map.of(
              "batchItemId",
              batchItemId,
              "workerId",
              workerId,
              "failureCode",
              bounded(failureCode),
              "now",
              Timestamp.from(now)));
    }
    touch(batchOperationId, now);
  }

  @Transactional
  public int cancelPending(String batchOperationId, Instant now) {
    var changed =
        jdbc.update(
            """
            UPDATE workspace_metadata_batch_operation_items
               SET state = 'CANCELLED',
                   failure_code = 'METADATA_BATCH_CANCELLED',
                   completed_at = :now
             WHERE batch_operation_id = :batchOperationId
               AND state = 'ACCEPTED'
            """,
            Map.of("batchOperationId", batchOperationId, "now", Timestamp.from(now)));
    touch(batchOperationId, now);
    return changed;
  }

  @Transactional
  public int failExpired(Instant now) {
    var changed =
        jdbc.update(
            """
            UPDATE workspace_metadata_batch_operation_items item
               SET state = 'FAILED',
                   failure_code = CASE
                     WHEN operation.deadline_at <= :now
                       THEN 'METADATA_BATCH_DEADLINE_EXCEEDED'
                     ELSE 'METADATA_BATCH_RETRY_EXHAUSTED'
                   END,
                   claim_owner = NULL,
                   claim_lease_until = NULL,
                   completed_at = :now
              FROM workspace_metadata_batch_operations operation
             WHERE operation.batch_operation_id = item.batch_operation_id
               AND (
                 (
                   operation.deadline_at <= :now
                   AND (
                     item.state = 'ACCEPTED'
                     OR (item.state = 'EXECUTING' AND item.claim_lease_until < :now)
                   )
                 )
                 OR
                 (
                   item.state = 'EXECUTING'
                   AND item.attempt >= 3
                   AND item.claim_lease_until < :now
                 )
               )
            """,
            Map.of("now", Timestamp.from(now)));
    if (changed > 0) {
      jdbc.update(
          """
          UPDATE workspace_metadata_batch_operations operation
             SET updated_at = :now
           WHERE EXISTS (
               SELECT 1
                 FROM workspace_metadata_batch_operation_items item
                WHERE item.batch_operation_id = operation.batch_operation_id
                  AND item.completed_at = :now
             )
          """,
          Map.of("now", Timestamp.from(now)));
    }
    return changed;
  }

  private void touch(String batchOperationId, Instant now) {
    jdbc.update(
        """
        UPDATE workspace_metadata_batch_operations
           SET updated_at = :now
         WHERE batch_operation_id = :batchOperationId
        """,
        Map.of("batchOperationId", batchOperationId, "now", Timestamp.from(now)));
  }

  private static String bounded(String failureCode) {
    var value =
        failureCode == null || failureCode.isBlank() ? "METADATA_BATCH_FAILED" : failureCode;
    return value.length() <= 240 ? value : value.substring(0, 240);
  }

  private static Object nullable(String value) {
    return value == null
        ? new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.VARCHAR, null)
        : value;
  }

  public record NewOperation(
      String batchOperationId,
      String tenantId,
      String actorId,
      String action,
      String selector,
      String targetGroupId,
      String targetTagIds,
      String reason,
      String requestHash,
      String idempotencyKey,
      Instant deadlineAt,
      Instant now) {}

  public record ClaimedOperation(String batchOperationId, String requestHash) {}

  public record ClaimedItem(
      String batchItemId,
      String batchOperationId,
      String tenantId,
      String sessionId,
      int attempt,
      String state,
      String claimOwner) {}

  public static final class WorkspaceMetadataBatchClaimRejectedException extends RuntimeException {
    public WorkspaceMetadataBatchClaimRejectedException(String reason) {
      super(reason);
    }
  }
}
