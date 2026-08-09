package io.browsercloud.application;

import static io.browsercloud.api.EnterpriseOperationsModels.ReleaseFreezeView;

import io.browsercloud.application.AuditApplicationService.AuditRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Error Budget Burn Rate 到 Runtime Promotion Gate 的 PostgreSQL 权威闭环。 */
@Service
public class ReleaseFreezeApplicationService {
  static final BigDecimal MAX_BURN_RATE = new BigDecimal("999.000000");

  private final JdbcTemplate jdbc;
  private final AuditApplicationService audit;
  private final Duration maximumStateAge;

  public ReleaseFreezeApplicationService(
      JdbcTemplate jdbc,
      AuditApplicationService audit,
      @Value("${enterprise.release-freeze-state-max-age-ms:120000}") long maximumStateAgeMillis) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.maximumStateAge = Duration.ofMillis(Math.max(30_000, maximumStateAgeMillis));
  }

  @Transactional
  public Optional<ReleaseFreezeView> evaluateTenant(String tenantId, Instant evaluatedAt) {
    var policy =
        jdbc
            .query(
                "SELECT * FROM enterprise_slo_policies WHERE tenant_id = ? FOR UPDATE",
                ReleaseFreezeApplicationService::mapPolicy,
                tenantId)
            .stream()
            .findFirst();
    if (policy.isEmpty()) return Optional.empty();

    var current =
        jdbc
            .query(
                "SELECT * FROM enterprise_release_freeze_states WHERE tenant_id = ? FOR UPDATE",
                ReleaseFreezeApplicationService::mapState,
                tenantId)
            .stream()
            .findFirst()
            .orElse(null);
    var burnRate = calculateBurnRate(policy.get(), evaluatedAt);
    var decision =
        decide(
            policy.get().enabled(),
            current != null && current.frozen(),
            current == null ? null : current.stableSince(),
            burnRate,
            policy.get().freezeThreshold(),
            policy.get().recoveryThreshold(),
            policy.get().recoveryStableMinutes(),
            evaluatedAt);
    var frozenAt =
        decision.frozen()
            ? current != null && current.frozen() ? current.frozenAt() : evaluatedAt
            : null;
    var clearedAt =
        decision.transition() == Transition.CLEARED
            ? evaluatedAt
            : current == null ? null : current.clearedAt();
    var version = current == null ? 1 : current.version() + 1;
    jdbc.update(
        """
        INSERT INTO enterprise_release_freeze_states(
          tenant_id, frozen, phase, current_burn_rate, reason_code,
          stable_since, frozen_at, cleared_at, evaluated_at, version
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id) DO UPDATE SET
          frozen = EXCLUDED.frozen,
          phase = EXCLUDED.phase,
          current_burn_rate = EXCLUDED.current_burn_rate,
          reason_code = EXCLUDED.reason_code,
          stable_since = EXCLUDED.stable_since,
          frozen_at = EXCLUDED.frozen_at,
          cleared_at = EXCLUDED.cleared_at,
          evaluated_at = EXCLUDED.evaluated_at,
          version = EXCLUDED.version
        """,
        tenantId,
        decision.frozen(),
        decision.phase(),
        burnRate,
        decision.reasonCode(),
        timestamp(decision.stableSince()),
        timestamp(frozenAt),
        timestamp(clearedAt),
        timestamp(evaluatedAt),
        version);

    if (decision.transition() != null) {
      recordTransition(policy.get(), decision, burnRate, evaluatedAt);
    }
    return Optional.of(
        view(policy.get(), decision, burnRate, frozenAt, clearedAt, evaluatedAt, version));
  }

  @Transactional
  public Optional<String> promotionBlockReason(String tenantId) {
    var now = Instant.now();
    var evaluated = evaluateTenant(tenantId, now);
    if (evaluated.isEmpty() || !evaluated.get().enabled()) return Optional.empty();
    var state = evaluated.get();
    if (state.evaluatedAt().isBefore(now.minus(maximumStateAge))) {
      return Optional.of("RELEASE_FREEZE_STATE_UNAVAILABLE");
    }
    if (state.frozen()) {
      return Optional.of("RELEASE_FROZEN_" + state.reasonCode());
    }
    return Optional.empty();
  }

  @Transactional(readOnly = true)
  public Optional<ReleaseFreezeView> current(String tenantId) {
    return jdbc
        .query(
            """
            SELECT p.tenant_id, p.release_freeze_enabled,
                   p.release_freeze_burn_rate_threshold,
                   p.release_recovery_burn_rate_threshold,
                   p.release_freeze_window_minutes,
                   p.release_recovery_stable_minutes,
                   s.frozen, s.phase, s.current_burn_rate, s.reason_code,
                   s.stable_since, s.frozen_at, s.cleared_at,
                   s.evaluated_at, s.version
            FROM enterprise_slo_policies p
            JOIN enterprise_release_freeze_states s ON s.tenant_id = p.tenant_id
            WHERE p.tenant_id = ?
            """,
            ReleaseFreezeApplicationService::mapView,
            tenantId)
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public List<String> enabledTenantsAfter(String afterTenantId, int limit) {
    return jdbc.queryForList(
        """
        SELECT tenant_id
        FROM enterprise_slo_policies
        WHERE release_freeze_enabled = TRUE AND tenant_id > ?
        ORDER BY tenant_id
        LIMIT ?
        """,
        String.class,
        afterTenantId,
        Math.max(1, Math.min(limit, 500)));
  }

  private BigDecimal calculateBurnRate(Policy policy, Instant evaluatedAt) {
    var windowStart = evaluatedAt.minus(Duration.ofMinutes(policy.evaluationWindowMinutes()));
    var consumed =
        Optional.ofNullable(
                jdbc.queryForObject(
                    """
                    SELECT COALESCE(sum(duration_seconds), 0)
                    FROM enterprise_service_level_events
                    WHERE tenant_id = ? AND event_type = 'UNAVAILABLE'
                      AND excluded_from_sla = FALSE
                      AND occurred_at >= ? AND occurred_at <= ?
                    """,
                    Long.class,
                    policy.tenantId(),
                    timestamp(windowStart),
                    timestamp(evaluatedAt)))
            .orElse(0L);
    var allowed =
        BigDecimal.valueOf(policy.evaluationWindowMinutes() * 60L)
            .multiply(BigDecimal.ONE.subtract(policy.availabilityTarget()))
            .setScale(0, RoundingMode.FLOOR)
            .longValue();
    if (allowed == 0) return consumed == 0 ? BigDecimal.ZERO : MAX_BURN_RATE;
    return BigDecimal.valueOf(consumed)
        .divide(BigDecimal.valueOf(allowed), 6, RoundingMode.HALF_UP);
  }

  private void recordTransition(
      Policy policy, Decision decision, BigDecimal burnRate, Instant occurredAt) {
    var eventId = "rfz_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    var threshold =
        decision.transition() == Transition.FROZEN
            ? policy.freezeThreshold()
            : policy.recoveryThreshold();
    jdbc.update(
        """
        INSERT INTO enterprise_release_freeze_events(
          freeze_event_id, tenant_id, transition, burn_rate, threshold,
          evaluation_window_minutes, reason_code, occurred_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        eventId,
        policy.tenantId(),
        decision.transition().name(),
        burnRate,
        threshold,
        policy.evaluationWindowMinutes(),
        decision.reasonCode(),
        timestamp(occurredAt));
    audit.append(
        new AuditRecord(
            policy.tenantId(),
            null,
            "RUNTIME_RELEASE",
            "SYSTEM",
            "release-freeze-controller",
            "TENANT",
            policy.tenantId(),
            decision.transition() == Transition.FROZEN
                ? "RUNTIME_RELEASE_AUTO_FROZEN"
                : "RUNTIME_RELEASE_AUTO_CLEARED",
            decision.transition().name(),
            Map.of(
                "freezeEventId",
                eventId,
                "burnRate",
                burnRate,
                "threshold",
                threshold,
                "evaluationWindowMinutes",
                policy.evaluationWindowMinutes(),
                "reasonCode",
                decision.reasonCode()),
            eventId));
  }

  static Decision decide(
      boolean enabled,
      boolean currentlyFrozen,
      Instant stableSince,
      BigDecimal burnRate,
      BigDecimal freezeThreshold,
      BigDecimal recoveryThreshold,
      int recoveryStableMinutes,
      Instant now) {
    if (!enabled) {
      return new Decision(
          false, "OPEN", "POLICY_DISABLED", null, currentlyFrozen ? Transition.CLEARED : null);
    }
    if (burnRate.compareTo(freezeThreshold) >= 0) {
      return new Decision(
          true,
          "FROZEN",
          "ERROR_BUDGET_BURN_RATE_EXCEEDED",
          null,
          currentlyFrozen ? null : Transition.FROZEN);
    }
    if (!currentlyFrozen) {
      return new Decision(false, "OPEN", "BURN_RATE_WITHIN_POLICY", null, null);
    }
    if (burnRate.compareTo(recoveryThreshold) <= 0) {
      var recoveryStartedAt = stableSince == null ? now : stableSince;
      if (!now.isBefore(recoveryStartedAt.plus(Duration.ofMinutes(recoveryStableMinutes)))) {
        return new Decision(false, "OPEN", "BURN_RATE_RECOVERED", null, Transition.CLEARED);
      }
      return new Decision(true, "RECOVERING", "RECOVERY_WINDOW_OBSERVING", recoveryStartedAt, null);
    }
    return new Decision(true, "FROZEN", "ERROR_BUDGET_RECOVERY_NOT_STABLE", null, null);
  }

  private static Policy mapPolicy(ResultSet result, int row) throws SQLException {
    return new Policy(
        result.getString("tenant_id"),
        result.getBigDecimal("availability_target"),
        result.getBoolean("release_freeze_enabled"),
        result.getBigDecimal("release_freeze_burn_rate_threshold"),
        result.getBigDecimal("release_recovery_burn_rate_threshold"),
        result.getInt("release_freeze_window_minutes"),
        result.getInt("release_recovery_stable_minutes"));
  }

  private static State mapState(ResultSet result, int row) throws SQLException {
    return new State(
        result.getBoolean("frozen"),
        instant(result, "stable_since"),
        instant(result, "frozen_at"),
        instant(result, "cleared_at"),
        result.getLong("version"));
  }

  private static ReleaseFreezeView mapView(ResultSet result, int row) throws SQLException {
    return new ReleaseFreezeView(
        result.getString("tenant_id"),
        result.getBoolean("release_freeze_enabled"),
        result.getString("phase"),
        result.getBoolean("frozen"),
        result.getBigDecimal("current_burn_rate"),
        result.getBigDecimal("release_freeze_burn_rate_threshold"),
        result.getBigDecimal("release_recovery_burn_rate_threshold"),
        result.getInt("release_freeze_window_minutes"),
        result.getInt("release_recovery_stable_minutes"),
        result.getString("reason_code"),
        instant(result, "stable_since"),
        instant(result, "frozen_at"),
        instant(result, "cleared_at"),
        instant(result, "evaluated_at"),
        result.getLong("version"));
  }

  private static ReleaseFreezeView view(
      Policy policy,
      Decision decision,
      BigDecimal burnRate,
      Instant frozenAt,
      Instant clearedAt,
      Instant evaluatedAt,
      long version) {
    return new ReleaseFreezeView(
        policy.tenantId(),
        policy.enabled(),
        decision.phase(),
        decision.frozen(),
        burnRate,
        policy.freezeThreshold(),
        policy.recoveryThreshold(),
        policy.evaluationWindowMinutes(),
        policy.recoveryStableMinutes(),
        decision.reasonCode(),
        decision.stableSince(),
        frozenAt,
        clearedAt,
        evaluatedAt,
        version);
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    var value = result.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  record Decision(
      boolean frozen,
      String phase,
      String reasonCode,
      Instant stableSince,
      Transition transition) {}

  enum Transition {
    FROZEN,
    CLEARED
  }

  private record Policy(
      String tenantId,
      BigDecimal availabilityTarget,
      boolean enabled,
      BigDecimal freezeThreshold,
      BigDecimal recoveryThreshold,
      int evaluationWindowMinutes,
      int recoveryStableMinutes) {}

  private record State(
      boolean frozen, Instant stableSince, Instant frozenAt, Instant clearedAt, long version) {}
}
