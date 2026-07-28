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

/**
 * Crash-recoverable physical shard claim for Node Command Outbox rows.
 *
 * <p>Active Control Plane workers register a PostgreSQL lease. Rendezvous Hash chooses exactly one
 * active worker for each Shard without a fixed replica count, so a normal Deployment rolling update
 * can add and remove Pods safely. Legacy N-1 rows have no Shard binding and may be drained by any
 * worker; {@code FOR UPDATE SKIP LOCKED} guarantees a single active claim while the row lease makes
 * worker crashes recoverable.
 */
@Service
public class NodeCommandDispatchClaimService {

  private static final int BATCH_SIZE = 100;

  private final NamedParameterJdbcTemplate jdbc;
  private final String workerId;
  private final Duration dispatchLeaseDuration;
  private final Duration workerLeaseDuration;

  public NodeCommandDispatchClaimService(
      NamedParameterJdbcTemplate jdbc,
      @Value("${coordinator.instance-id:${random.uuid}}") String workerId,
      @Value("${coordinator.dispatch-lease-seconds:6}") long dispatchLeaseSeconds,
      @Value("${coordinator.dispatch-worker-lease-seconds:3}") long workerLeaseSeconds) {
    if (workerId == null || workerId.isBlank()) {
      throw new IllegalArgumentException("coordinator.instance-id is required");
    }
    if (dispatchLeaseSeconds < 6 || dispatchLeaseSeconds > 300) {
      throw new IllegalArgumentException(
          "coordinator.dispatch-lease-seconds must be between 6 and 300");
    }
    if (workerLeaseSeconds < 2 || workerLeaseSeconds > 60) {
      throw new IllegalArgumentException(
          "coordinator.dispatch-worker-lease-seconds must be between 2 and 60");
    }
    this.jdbc = jdbc;
    this.workerId = workerId;
    this.dispatchLeaseDuration = Duration.ofSeconds(dispatchLeaseSeconds);
    this.workerLeaseDuration = Duration.ofSeconds(workerLeaseSeconds);
  }

  @Transactional
  public List<String> claimReady(Instant now) {
    heartbeat(now);
    var parameters =
        Map.<String, Object>of(
            "eventType",
            PostgresNodeCommandGateway.NODE_COMMAND_EVENT,
            "now",
            Timestamp.from(now),
            "leaseUntil",
            Timestamp.from(now.plus(dispatchLeaseDuration)),
            "workerId",
            workerId,
            "batchSize",
            BATCH_SIZE);
    return jdbc.query(
        """
        WITH candidates AS (
          SELECT event_id
            FROM outbox_events
           WHERE published_at IS NULL
             AND dead_lettered_at IS NULL
             AND event_type = :eventType
             AND next_attempt_at <= :now
             AND (dispatch_lease_until IS NULL OR dispatch_lease_until < :now)
             AND (
               coordinator_shard_id IS NULL
               OR :workerId = (
                 SELECT worker.worker_id
                   FROM coordinator_dispatch_workers worker
                  WHERE worker.lease_until >= :now
                  ORDER BY
                    hashtextextended(
                      worker.worker_id || ':' || coordinator_shard_id::text,
                      0
                    ) DESC,
                    worker.worker_id
                  LIMIT 1
               )
             )
           ORDER BY next_attempt_at, created_at, event_id
           LIMIT :batchSize
           FOR UPDATE SKIP LOCKED
        )
        UPDATE outbox_events event
           SET dispatch_owner = :workerId,
               dispatch_lease_until = :leaseUntil
          FROM candidates
         WHERE event.event_id = candidates.event_id
        RETURNING event.event_id
        """,
        parameters,
        (resultSet, rowNumber) -> resultSet.getString("event_id"));
  }

  private void heartbeat(Instant now) {
    jdbc.update(
        """
        INSERT INTO coordinator_dispatch_workers(
            worker_id, started_at, heartbeat_at, lease_until
        ) VALUES (
            :workerId, :now, :now, :leaseUntil
        )
        ON CONFLICT(worker_id) DO UPDATE SET
            heartbeat_at = excluded.heartbeat_at,
            lease_until = excluded.lease_until
        """,
        Map.of(
            "workerId",
            workerId,
            "now",
            Timestamp.from(now),
            "leaseUntil",
            Timestamp.from(now.plus(workerLeaseDuration))));
  }

  @Transactional
  public void unregister() {
    jdbc.update(
        "DELETE FROM coordinator_dispatch_workers WHERE worker_id = :workerId",
        Map.of("workerId", workerId));
  }

  public String workerId() {
    return workerId;
  }
}
