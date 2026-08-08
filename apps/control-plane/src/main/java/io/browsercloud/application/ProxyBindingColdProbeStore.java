package io.browsercloud.application;

import io.browsercloud.application.ProxyBindingProbeNodeGateway.ProbeResult;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-authoritative cold probe claims and retry scheduling. */
@Service
public class ProxyBindingColdProbeStore {

  private final JdbcTemplate jdbc;
  private final ProxyBindingHealthApplicationService health;
  private final Duration leaseDuration;
  private final Duration successInterval;
  private final Duration failureInterval;
  private final Duration unavailableRetry;

  public ProxyBindingColdProbeStore(
      JdbcTemplate jdbc,
      ProxyBindingHealthApplicationService health,
      @Value("${proxy.health.cold-probe-lease-seconds:45}") long leaseSeconds,
      @Value("${proxy.health.cold-probe-success-interval-seconds:900}") long successSeconds,
      @Value("${proxy.health.cold-probe-failure-interval-seconds:120}") long failureSeconds,
      @Value("${proxy.health.cold-probe-unavailable-retry-seconds:30}") long unavailableSeconds) {
    requireRange(leaseSeconds, 15, 300, "cold probe lease");
    requireRange(successSeconds, 60, 86400, "cold probe success interval");
    requireRange(failureSeconds, 30, 3600, "cold probe failure interval");
    requireRange(unavailableSeconds, 10, 600, "cold probe unavailable retry");
    this.jdbc = jdbc;
    this.health = health;
    this.leaseDuration = Duration.ofSeconds(leaseSeconds);
    this.successInterval = Duration.ofSeconds(successSeconds);
    this.failureInterval = Duration.ofSeconds(failureSeconds);
    this.unavailableRetry = Duration.ofSeconds(unavailableSeconds);
  }

  @Transactional
  public List<ColdProbeClaim> claimDue(int limit, Instant now) {
    if (limit < 1 || limit > 64) {
      throw new IllegalArgumentException("Cold probe claim limit must be between 1 and 64");
    }
    var candidates =
        jdbc.query(
            """
            SELECT p.binding_profile_id, p.tenant_id, p.provider_id, p.region,
                   p.expected_exit_ip, p.credential_ref, p.version
            FROM proxy_binding_profiles p
            WHERE p.enabled
              AND p.next_cold_probe_at <= ?
              AND (p.cold_probe_lease_until IS NULL OR p.cold_probe_lease_until < ?)
              AND NOT EXISTS (
                  SELECT 1
                  FROM proxy_allocations allocation
                  WHERE allocation.tenant_id = p.tenant_id
                    AND allocation.binding_profile_id = p.binding_profile_id
                    AND allocation.state IN ('ALLOCATED', 'BOUND')
              )
            ORDER BY p.next_cold_probe_at, p.binding_profile_id
            FOR UPDATE OF p SKIP LOCKED
            LIMIT ?
            """,
            (resultSet, rowNumber) ->
                new ColdProbeClaim(
                    null,
                    resultSet.getString("tenant_id"),
                    resultSet.getString("binding_profile_id"),
                    resultSet.getString("provider_id"),
                    resultSet.getString("region"),
                    resultSet.getString("expected_exit_ip"),
                    resultSet.getString("credential_ref"),
                    resultSet.getLong("version")),
            Timestamp.from(now),
            Timestamp.from(now),
            limit);
    var claimed = new ArrayList<ColdProbeClaim>(candidates.size());
    for (var candidate : candidates) {
      var probeId = "prb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
      var updated =
          jdbc.update(
              """
              UPDATE proxy_binding_profiles
              SET cold_probe_lease_owner = ?, cold_probe_lease_until = ?
              WHERE binding_profile_id = ? AND tenant_id = ? AND version = ?
              """,
              probeId,
              Timestamp.from(now.plus(leaseDuration)),
              candidate.bindingProfileId(),
              candidate.tenantId(),
              candidate.bindingVersion());
      if (updated == 1) {
        claimed.add(candidate.withProbeId(probeId));
      }
    }
    return List.copyOf(claimed);
  }

  @Transactional
  public boolean complete(ColdProbeClaim claim, ProbeResult result, Instant observedAt) {
    if (!claim.probeId().equals(result.probeId())
        || !claim.bindingProfileId().equals(result.bindingProfileId())) {
      throw new IllegalArgumentException("Cold probe result does not match the claim");
    }
    if (!health.recordColdProbe(claim, result, observedAt)) {
      return false;
    }
    var next = observedAt.plus(result.succeeded() ? successInterval : failureInterval);
    return clearLease(claim, next) == 1;
  }

  @Transactional
  public boolean retryUnavailable(ColdProbeClaim claim, Instant now) {
    return clearLease(claim, now.plus(unavailableRetry)) == 1;
  }

  private int clearLease(ColdProbeClaim claim, Instant nextProbeAt) {
    return jdbc.update(
        """
        UPDATE proxy_binding_profiles
        SET cold_probe_lease_owner = NULL,
            cold_probe_lease_until = NULL,
            next_cold_probe_at = ?
        WHERE binding_profile_id = ? AND tenant_id = ? AND version = ?
          AND cold_probe_lease_owner = ?
        """,
        Timestamp.from(nextProbeAt),
        claim.bindingProfileId(),
        claim.tenantId(),
        claim.bindingVersion(),
        claim.probeId());
  }

  private static void requireRange(long value, long minimum, long maximum, String name) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(
          name + " must be between " + minimum + " and " + maximum + " seconds");
    }
  }

  public record ColdProbeClaim(
      String probeId,
      String tenantId,
      String bindingProfileId,
      String providerId,
      String region,
      String expectedExitIp,
      String credentialRef,
      long bindingVersion) {

    ColdProbeClaim withProbeId(String value) {
      return new ColdProbeClaim(
          value,
          tenantId,
          bindingProfileId,
          providerId,
          region,
          expectedExitIp,
          credentialRef,
          bindingVersion);
    }
  }
}
