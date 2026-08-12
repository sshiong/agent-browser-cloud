package io.browsercloud.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Prices real RFB egress deltas without exposing actor identifiers as metric labels. */
@Service
public class RemoteDesktopUsageCostAttributionService {
  private static final BigDecimal BYTES_PER_GIB = BigDecimal.valueOf(1024L * 1024L * 1024L);
  private final JdbcTemplate jdbc;

  public RemoteDesktopUsageCostAttributionService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<ResolvedEgressRate> resolve(
      String tenantId, String sessionId, Instant observedAt) {
    return jdbc
        .query(
            """
            SELECT rate.pricing_version, rate.remote_desktop_egress_gib_usd
              FROM browser_placements placement
              JOIN browser_nodes node ON node.node_id = placement.node_id
              JOIN enterprise_cost_rates rate
                ON rate.region = node.region
               AND rate.resource_template = CASE placement.effective_resource_class
                 WHEN 'L0' THEN 'suspended-v1'
                 WHEN 'L1' THEN 'standard-lite-v1'
                 WHEN 'L2' THEN 'standard-v1'
                 WHEN 'L3' THEN 'interactive-v1'
                 WHEN 'L4' THEN 'heavy-v1'
                 WHEN 'L5' THEN 'native-standard-v1'
               END
               AND rate.effective_at <= ?
             WHERE placement.tenant_id = ? AND placement.session_id = ?
             ORDER BY rate.effective_at DESC
             LIMIT 1
            """,
            (result, row) ->
                new ResolvedEgressRate(
                    result.getString("pricing_version"),
                    result.getBigDecimal("remote_desktop_egress_gib_usd")),
            Timestamp.from(observedAt),
            tenantId,
            sessionId)
        .stream()
        .findFirst();
  }

  public BigDecimal price(long bytes, ResolvedEgressRate rate) {
    if (bytes <= 0) return BigDecimal.ZERO.setScale(9);
    return rate.egressGibUsd()
        .multiply(BigDecimal.valueOf(bytes))
        .divide(BYTES_PER_GIB, 9, RoundingMode.HALF_UP);
  }

  public void appendLedger(
      String eventId,
      String connectionId,
      String tenantId,
      String sessionId,
      String actorId,
      long deltaBytes,
      long deltaWaitMillis,
      long deltaBatches,
      ResolvedEgressRate rate,
      BigDecimal attributedCost,
      Instant observedAt) {
    if (deltaBytes == 0 && deltaWaitMillis == 0 && deltaBatches == 0) return;
    jdbc.update(
        """
        INSERT INTO remote_desktop_usage_ledger(
          event_id, connection_id, tenant_id, session_id, actor_id,
          delta_forwarded_bytes, delta_quota_wait_millis, delta_throttled_batches,
          pricing_version, egress_gib_usd, attributed_cost_usd, observed_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (event_id) DO NOTHING
        """,
        eventId,
        connectionId,
        tenantId,
        sessionId,
        actorId,
        deltaBytes,
        deltaWaitMillis,
        deltaBatches,
        rate == null ? null : rate.pricingVersion(),
        rate == null ? null : rate.egressGibUsd(),
        attributedCost,
        Timestamp.from(observedAt));
  }

  public record ResolvedEgressRate(String pricingVersion, BigDecimal egressGibUsd) {}
}
