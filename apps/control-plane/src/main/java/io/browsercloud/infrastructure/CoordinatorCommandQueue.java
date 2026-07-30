package io.browsercloud.infrastructure;

import io.browsercloud.coordinator.CoordinatorRouteAuthority.SessionRoute;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-authoritative inbox for physically routed Coordinator commands. */
@Service
public class CoordinatorCommandQueue {

  private static final int CLAIM_BATCH_SIZE = 50;

  private final NamedParameterJdbcTemplate jdbc;
  private final NodeCommandDispatchClaimService workerMembership;
  private final Duration claimLease;

  public CoordinatorCommandQueue(
      NamedParameterJdbcTemplate jdbc,
      NodeCommandDispatchClaimService workerMembership,
      @Value("${coordinator.command-claim-lease-seconds:30}") long claimLeaseSeconds) {
    if (claimLeaseSeconds < 10 || claimLeaseSeconds > 300) {
      throw new IllegalArgumentException(
          "coordinator.command-claim-lease-seconds must be between 10 and 300");
    }
    this.jdbc = jdbc;
    this.workerMembership = workerMembership;
    this.claimLease = Duration.ofSeconds(claimLeaseSeconds);
  }

  public CommandRecord enqueue(
      SessionRoute route,
      String commandType,
      String deduplicationKey,
      String payload,
      Instant deadline) {
    var candidateId = newId();
    var now = Instant.now();
    jdbc.update(
        """
        INSERT INTO coordinator_commands(
            command_id, tenant_id, session_id, route_epoch, coordinator_shard_id,
            command_type, deduplication_key, payload, deadline_at, created_at,
            next_attempt_at
        ) VALUES (
            :commandId, :tenantId, :sessionId, :routeEpoch, :shardId,
            :commandType, :deduplicationKey, CAST(:payload AS jsonb), :deadline,
            :now, :now
        )
        ON CONFLICT(tenant_id, deduplication_key) DO NOTHING
        """,
        Map.of(
            "commandId",
            candidateId,
            "tenantId",
            route.tenantId(),
            "sessionId",
            route.sessionId(),
            "routeEpoch",
            route.routeEpoch(),
            "shardId",
            route.shardId(),
            "commandType",
            commandType,
            "deduplicationKey",
            deduplicationKey,
            "payload",
            payload,
            "deadline",
            Timestamp.from(deadline),
            "now",
            Timestamp.from(now)));
    return requireByDeduplication(route.tenantId(), deduplicationKey);
  }

  @Transactional
  public List<String> claimReady(Instant now) {
    workerMembership.heartbeatWorker(now);
    jdbc.update(
        """
        UPDATE coordinator_commands command
           SET route_epoch = route.route_epoch,
               coordinator_shard_id = route.shard_id
          FROM coordinator_session_routes route
         WHERE command.session_id = route.session_id
           AND command.tenant_id = route.tenant_id
           AND command.state = 'PENDING'
           AND (
             command.route_epoch <> route.route_epoch
             OR command.coordinator_shard_id <> route.shard_id
           )
        """,
        Map.of());
    return jdbc.query(
        """
        WITH candidates AS (
          SELECT command.command_id
            FROM coordinator_commands command
            JOIN coordinator_session_routes route
              ON route.session_id = command.session_id
             AND route.tenant_id = command.tenant_id
             AND route.route_epoch = command.route_epoch
             AND route.shard_id = command.coordinator_shard_id
           WHERE command.state = 'PENDING'
             AND command.next_attempt_at <= :now
             AND command.deadline_at > :now
             AND (
               command.claim_lease_until IS NULL
               OR command.claim_lease_until < :now
             )
             AND :workerId = (
               SELECT worker.worker_id
                 FROM coordinator_dispatch_workers worker
                WHERE worker.lease_until >= :now
                ORDER BY
                  hashtextextended(
                    worker.worker_id || ':' || command.coordinator_shard_id::text,
                    0
                  ) DESC,
                  worker.worker_id
                LIMIT 1
             )
           ORDER BY command.next_attempt_at, command.created_at, command.command_id
           LIMIT :batchSize
           FOR UPDATE OF command SKIP LOCKED
        )
        UPDATE coordinator_commands command
           SET state = 'EXECUTING',
               claim_owner = :workerId,
               claim_lease_until = :leaseUntil,
               attempt = command.attempt + 1,
               started_at = COALESCE(command.started_at, :now)
          FROM candidates
         WHERE command.command_id = candidates.command_id
        RETURNING command.command_id
        """,
        Map.of(
            "now",
            Timestamp.from(now),
            "workerId",
            workerMembership.workerId(),
            "leaseUntil",
            Timestamp.from(now.plus(claimLease)),
            "batchSize",
            CLAIM_BATCH_SIZE),
        (resultSet, rowNumber) -> resultSet.getString("command_id"));
  }

  @Transactional
  public CommandRecord requireClaimedForUpdate(String commandId) {
    var commands =
        jdbc.query(
            """
            SELECT command_id, tenant_id, session_id, route_epoch, coordinator_shard_id,
                   command_type, deduplication_key, payload::text, state, result::text,
                   failure_code, attempt, claim_owner, claim_lease_until, deadline_at,
                   created_at, started_at, completed_at
              FROM coordinator_commands
             WHERE command_id = :commandId
             FOR UPDATE
            """,
            Map.of("commandId", commandId),
            this::map);
    if (commands.isEmpty()) {
      throw new CoordinatorCommandRejectedException("COORDINATOR_COMMAND_NOT_FOUND");
    }
    var command = commands.getFirst();
    if (!"EXECUTING".equals(command.state())
        || !workerMembership.workerId().equals(command.claimOwner())) {
      throw new CoordinatorCommandRejectedException("COORDINATOR_COMMAND_CLAIM_LOST");
    }
    if (command.deadlineAt().isBefore(Instant.now())) {
      throw new CoordinatorCommandRejectedException("COORDINATOR_COMMAND_DEADLINE_EXCEEDED");
    }
    return command;
  }

  @Transactional
  public void commit(String commandId, String result, Instant now) {
    var changed =
        jdbc.update(
            """
            UPDATE coordinator_commands
               SET state = 'COMMITTED',
                   result = CAST(:result AS jsonb),
                   failure_code = NULL,
                   claim_owner = NULL,
                   claim_lease_until = NULL,
                   completed_at = :now
             WHERE command_id = :commandId
               AND state = 'EXECUTING'
               AND claim_owner = :workerId
            """,
            Map.of(
                "commandId",
                commandId,
                "workerId",
                workerMembership.workerId(),
                "result",
                result,
                "now",
                Timestamp.from(now)));
    if (changed != 1) {
      throw new CoordinatorCommandRejectedException("COORDINATOR_COMMAND_COMMIT_FENCED");
    }
  }

  @Transactional
  public void retryOrFail(String commandId, String failureCode, Instant now, int maximumAttempts) {
    var command = require(commandId);
    var boundedFailure = bounded(failureCode);
    if (command.attempt() < maximumAttempts && command.deadlineAt().isAfter(now.plusSeconds(1))) {
      jdbc.update(
          """
          UPDATE coordinator_commands
             SET state = 'PENDING',
                 failure_code = NULL,
                 claim_owner = NULL,
                 claim_lease_until = NULL,
                 next_attempt_at = :nextAttempt
           WHERE command_id = :commandId
             AND state = 'EXECUTING'
             AND claim_owner = :workerId
          """,
          Map.of(
              "commandId",
              commandId,
              "workerId",
              workerMembership.workerId(),
              "nextAttempt",
              Timestamp.from(now.plusSeconds(1))));
      return;
    }
    jdbc.update(
        """
        UPDATE coordinator_commands
           SET state = 'FAILED',
               result = NULL,
               failure_code = :failureCode,
               claim_owner = NULL,
               claim_lease_until = NULL,
               completed_at = :now
         WHERE command_id = :commandId
           AND state = 'EXECUTING'
           AND claim_owner = :workerId
        """,
        Map.of(
            "commandId",
            commandId,
            "workerId",
            workerMembership.workerId(),
            "failureCode",
            boundedFailure,
            "now",
            Timestamp.from(now)));
  }

  @Transactional
  public void failExpired(Instant now) {
    jdbc.update(
        """
        UPDATE coordinator_commands
           SET state = 'FAILED',
               failure_code = 'COORDINATOR_COMMAND_DEADLINE_EXCEEDED',
               claim_owner = NULL,
               claim_lease_until = NULL,
               completed_at = :now
         WHERE state = 'PENDING'
           AND deadline_at <= :now
        """,
        Map.of("now", Timestamp.from(now)));
  }

  public CommandRecord require(String commandId) {
    var commands =
        jdbc.query(
            """
            SELECT command_id, tenant_id, session_id, route_epoch, coordinator_shard_id,
                   command_type, deduplication_key, payload::text, state, result::text,
                   failure_code, attempt, claim_owner, claim_lease_until, deadline_at,
                   created_at, started_at, completed_at
              FROM coordinator_commands
             WHERE command_id = :commandId
            """,
            Map.of("commandId", commandId),
            this::map);
    if (commands.isEmpty()) {
      throw new CoordinatorCommandRejectedException("COORDINATOR_COMMAND_NOT_FOUND");
    }
    return commands.getFirst();
  }

  private CommandRecord requireByDeduplication(String tenantId, String deduplicationKey) {
    var commands =
        jdbc.query(
            """
            SELECT command_id, tenant_id, session_id, route_epoch, coordinator_shard_id,
                   command_type, deduplication_key, payload::text, state, result::text,
                   failure_code, attempt, claim_owner, claim_lease_until, deadline_at,
                   created_at, started_at, completed_at
              FROM coordinator_commands
             WHERE tenant_id = :tenantId
               AND deduplication_key = :deduplicationKey
            """,
            Map.of("tenantId", tenantId, "deduplicationKey", deduplicationKey),
            this::map);
    if (commands.isEmpty()) {
      throw new CoordinatorCommandRejectedException("COORDINATOR_COMMAND_ENQUEUE_FAILED");
    }
    return commands.getFirst();
  }

  private CommandRecord map(java.sql.ResultSet resultSet, int rowNumber)
      throws java.sql.SQLException {
    return new CommandRecord(
        resultSet.getString("command_id"),
        resultSet.getString("tenant_id"),
        resultSet.getString("session_id"),
        resultSet.getLong("route_epoch"),
        resultSet.getInt("coordinator_shard_id"),
        resultSet.getString("command_type"),
        resultSet.getString("deduplication_key"),
        resultSet.getString("payload"),
        resultSet.getString("state"),
        resultSet.getString("result"),
        resultSet.getString("failure_code"),
        resultSet.getInt("attempt"),
        resultSet.getString("claim_owner"),
        timestamp(resultSet, "claim_lease_until"),
        resultSet.getTimestamp("deadline_at").toInstant(),
        resultSet.getTimestamp("created_at").toInstant(),
        timestamp(resultSet, "started_at"),
        timestamp(resultSet, "completed_at"));
  }

  private static Instant timestamp(java.sql.ResultSet resultSet, String column)
      throws java.sql.SQLException {
    var value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String bounded(String value) {
    var normalized = value == null || value.isBlank() ? "COORDINATOR_COMMAND_FAILED" : value;
    return normalized.substring(0, Math.min(normalized.length(), 240));
  }

  private static String newId() {
    return "ccmd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  public record CommandRecord(
      String commandId,
      String tenantId,
      String sessionId,
      long routeEpoch,
      int coordinatorShardId,
      String commandType,
      String deduplicationKey,
      String payload,
      String state,
      String result,
      String failureCode,
      int attempt,
      String claimOwner,
      Instant claimLeaseUntil,
      Instant deadlineAt,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt) {}

  public static final class CoordinatorCommandRejectedException extends RuntimeException {
    public CoordinatorCommandRejectedException(String reason) {
      super(reason);
    }
  }
}
