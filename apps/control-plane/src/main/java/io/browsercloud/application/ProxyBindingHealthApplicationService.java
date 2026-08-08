package io.browsercloud.application;

import io.browsercloud.persistence.ProxyAllocationEntity;
import io.browsercloud.persistence.ProxyAllocationJpaRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Credential-free active Proxy health samples and atomic quality hysteresis. */
@Service
public class ProxyBindingHealthApplicationService {

  private static final List<String> ACTIVE_ALLOCATION_STATES = List.of("BOUND");
  private static final Set<String> FAILURE_CODES =
      Set.of("TIMEOUT", "CIRCUIT_OPEN", "EXIT_MISMATCH", "HELPER_UNAVAILABLE", "PROBE_FAILED");

  private final JdbcTemplate jdbc;
  private final ProxyAllocationJpaRepository allocations;
  private final Duration retention;

  public ProxyBindingHealthApplicationService(
      JdbcTemplate jdbc,
      ProxyAllocationJpaRepository allocations,
      @Value("${proxy.health.sample-retention-days:7}") long retentionDays) {
    if (retentionDays < 1 || retentionDays > 30) {
      throw new IllegalArgumentException("Proxy health retention must be between 1 and 30 days");
    }
    this.jdbc = jdbc;
    this.allocations = allocations;
    this.retention = Duration.ofDays(retentionDays);
  }

  @Transactional
  public void recordRuntimeVerified(
      ProxyAllocationEntity allocation, String nodeId, String observedExitIp, Instant observedAt) {
    if (allocation.getBindingProfileId() == null) {
      return;
    }
    insertSample(allocation, nodeId, "RUNTIME_BIND", true, null, observedExitIp, null, observedAt);
    updateSuccess(allocation, nodeId, "RUNTIME_BIND", null, observedExitIp, observedAt);
  }

  @Transactional
  public void recordNodeProbe(
      String sessionId,
      String tenantId,
      String nodeId,
      NodeProbeObservation observation,
      Instant observedAt) {
    var allocation =
        allocations
            .findFirstBySessionIdAndStateIn(sessionId, ACTIVE_ALLOCATION_STATES)
            .orElseThrow(
                () -> new ProxyHealthRejectedException("ACTIVE_PROXY_ALLOCATION_NOT_FOUND"));
    if (!allocation.getTenantId().equals(tenantId)
        || !allocation.getSessionId().equals(sessionId)) {
      throw new ProxyHealthRejectedException("PROXY_ALLOCATION_IDENTITY_MISMATCH");
    }
    if (allocation.getBindingProfileId() == null) {
      return;
    }
    var succeeded = observation.succeeded();
    var observedExitIp = blankToNull(observation.observedExitIp());
    var failureCode = blankToNull(observation.failureCode());
    if (succeeded
        && (observedExitIp == null || !observedExitIp.equals(allocation.getExpectedExitIp()))) {
      succeeded = false;
      observedExitIp = null;
      failureCode = "EXIT_MISMATCH";
    }
    if (succeeded && failureCode != null
        || !succeeded && (failureCode == null || !FAILURE_CODES.contains(failureCode))) {
      throw new ProxyHealthRejectedException("INVALID_PROXY_PROBE_RESULT");
    }

    insertSample(
        allocation,
        nodeId,
        "ACTIVE_EXIT_PROBE",
        succeeded,
        observation.latencyMs(),
        observedExitIp,
        failureCode,
        observedAt);
    if (succeeded) {
      updateSuccess(
          allocation,
          nodeId,
          "ACTIVE_EXIT_PROBE",
          observation.latencyMs(),
          observedExitIp,
          observedAt);
    } else {
      updateFailure(allocation, nodeId, observation.latencyMs(), failureCode, observedAt);
    }
  }

  private void insertSample(
      ProxyAllocationEntity allocation,
      String nodeId,
      String source,
      boolean succeeded,
      Integer latencyMs,
      String observedExitIp,
      String failureCode,
      Instant observedAt) {
    var inserted =
        jdbc.update(
            """
            INSERT INTO proxy_binding_health_samples(
                probe_id, binding_profile_id, tenant_id, allocation_id, session_id,
                node_id, source, succeeded, latency_ms, observed_exit_ip, failure_code,
                observed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "prb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
            allocation.getBindingProfileId(),
            allocation.getTenantId(),
            allocation.getAllocationId(),
            allocation.getSessionId(),
            nodeId,
            source,
            succeeded,
            latencyMs,
            observedExitIp,
            failureCode,
            Timestamp.from(observedAt));
    if (inserted != 1) {
      throw new ProxyHealthRejectedException("PROXY_HEALTH_SAMPLE_NOT_PERSISTED");
    }
  }

  private void updateSuccess(
      ProxyAllocationEntity allocation,
      String nodeId,
      String source,
      Integer latencyMs,
      String observedExitIp,
      Instant observedAt) {
    var sql =
        latencyMs == null
            ? """
              UPDATE proxy_binding_profiles
              SET health_state = CASE
                      WHEN NOT enabled THEN 'DISABLED'
                      WHEN ? = 'RUNTIME_BIND' THEN 'HEALTHY'
                      WHEN health_state = 'UNHEALTHY'
                           AND consecutive_probe_successes + 1 < 2 THEN 'UNHEALTHY'
                      ELSE 'HEALTHY'
                  END,
                  last_verified_exit_ip = ?,
                  last_health_checked_at = ?,
                  last_failure_reason = CASE
                      WHEN health_state = 'UNHEALTHY'
                           AND ? <> 'RUNTIME_BIND'
                           AND consecutive_probe_successes + 1 < 2
                      THEN last_failure_reason
                      ELSE NULL
                  END,
                  probe_success_count = probe_success_count + 1,
                  consecutive_probe_successes = LEAST(
                      consecutive_probe_successes + 1, 1000000
                  ),
                  consecutive_probe_failures = 0,
                  probe_success_ewma = ROUND(
                      COALESCE(probe_success_ewma * 0.8 + 0.2, 1.0), 5
                  ),
                  last_probe_session_id = ?,
                  last_probe_node_id = ?
              WHERE binding_profile_id = ? AND tenant_id = ?
              """
            : """
              UPDATE proxy_binding_profiles
              SET health_state = CASE
                      WHEN NOT enabled THEN 'DISABLED'
                      WHEN ? = 'RUNTIME_BIND' THEN 'HEALTHY'
                      WHEN health_state = 'UNHEALTHY'
                           AND consecutive_probe_successes + 1 < 2 THEN 'UNHEALTHY'
                      ELSE 'HEALTHY'
                  END,
                  last_verified_exit_ip = ?,
                  last_health_checked_at = ?,
                  last_failure_reason = CASE
                      WHEN health_state = 'UNHEALTHY'
                           AND ? <> 'RUNTIME_BIND'
                           AND consecutive_probe_successes + 1 < 2
                      THEN last_failure_reason
                      ELSE NULL
                  END,
                  probe_success_count = probe_success_count + 1,
                  consecutive_probe_successes = LEAST(
                      consecutive_probe_successes + 1, 1000000
                  ),
                  consecutive_probe_failures = 0,
                  probe_success_ewma = ROUND(
                      COALESCE(probe_success_ewma * 0.8 + 0.2, 1.0), 5
                  ),
                  probe_latency_ewma_ms = ROUND(
                      COALESCE(probe_latency_ewma_ms * 0.8 + ? * 0.2, ?), 3
                  ),
                  last_probe_session_id = ?,
                  last_probe_node_id = ?
              WHERE binding_profile_id = ? AND tenant_id = ?
              """;
    var common =
        new Object[] {
          source,
          observedExitIp,
          Timestamp.from(observedAt),
          source,
          allocation.getSessionId(),
          nodeId,
          allocation.getBindingProfileId(),
          allocation.getTenantId()
        };
    var updated =
        latencyMs == null
            ? jdbc.update(sql, common)
            : jdbc.update(
                sql,
                source,
                observedExitIp,
                Timestamp.from(observedAt),
                source,
                latencyMs,
                latencyMs,
                allocation.getSessionId(),
                nodeId,
                allocation.getBindingProfileId(),
                allocation.getTenantId());
    if (updated != 1) {
      throw new ProxyHealthRejectedException("PROXY_BINDING_NOT_FOUND");
    }
  }

  private void updateFailure(
      ProxyAllocationEntity allocation,
      String nodeId,
      int latencyMs,
      String failureCode,
      Instant observedAt) {
    var updated =
        jdbc.update(
            """
            UPDATE proxy_binding_profiles
            SET health_state = CASE
                    WHEN NOT enabled THEN 'DISABLED'
                    WHEN consecutive_probe_failures + 1 >= 3 THEN 'UNHEALTHY'
                    ELSE health_state
                END,
                last_health_checked_at = ?,
                last_failure_reason = ?,
                probe_failure_count = probe_failure_count + 1,
                consecutive_probe_successes = 0,
                consecutive_probe_failures = LEAST(
                    consecutive_probe_failures + 1, 1000000
                ),
                probe_success_ewma = ROUND(COALESCE(probe_success_ewma * 0.8, 0.0), 5),
                probe_latency_ewma_ms = ROUND(
                    COALESCE(probe_latency_ewma_ms * 0.8 + ? * 0.2, ?), 3
                ),
                last_probe_session_id = ?,
                last_probe_node_id = ?
            WHERE binding_profile_id = ? AND tenant_id = ?
            """,
            Timestamp.from(observedAt),
            failureCode,
            latencyMs,
            latencyMs,
            allocation.getSessionId(),
            nodeId,
            allocation.getBindingProfileId(),
            allocation.getTenantId());
    if (updated != 1) {
      throw new ProxyHealthRejectedException("PROXY_BINDING_NOT_FOUND");
    }
  }

  @Scheduled(cron = "${proxy.health.retention-cron:0 17 * * * *}")
  @Transactional
  public void purgeExpiredSamples() {
    jdbc.update(
        """
        DELETE FROM proxy_binding_health_samples
        WHERE probe_id IN (
            SELECT probe_id
            FROM proxy_binding_health_samples
            WHERE received_at < ?
            ORDER BY received_at
            LIMIT 10000
        )
        """,
        Timestamp.from(Instant.now().minus(retention)));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public record NodeProbeObservation(
      boolean succeeded, int latencyMs, String observedExitIp, String failureCode) {
    public NodeProbeObservation {
      if (latencyMs < 0 || latencyMs > 30000) {
        throw new IllegalArgumentException("Proxy probe latency must be between 0 and 30000 ms");
      }
    }
  }

  public static final class ProxyHealthRejectedException extends RuntimeException {
    public ProxyHealthRejectedException(String reason) {
      super(reason);
    }
  }
}
