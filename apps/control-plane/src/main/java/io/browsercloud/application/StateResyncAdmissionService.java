package io.browsercloud.application;

import io.browsercloud.api.StateResyncRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-authoritative weighted State Resync budgets and automatic-loop circuit breaker. */
@Service
public class StateResyncAdmissionService {

  static final int FULL_TOKEN_COST = 10;
  static final int REGION_TOKEN_COST = 2;
  static final long FULL_RESERVED_BYTES = 512L * 1024;
  static final long REGION_RESERVED_BYTES = 128L * 1024;
  static final long FULL_RESERVED_CPU_MILLIS = 2_000;
  static final long REGION_RESERVED_CPU_MILLIS = 500;

  private final JdbcTemplate jdbc;
  private final AuditApplicationService auditService;
  private final Duration budgetWindow;
  private final int sessionTokenLimit;
  private final int tenantTokenLimit;
  private final Duration automaticCircuitWindow;
  private final int automaticCircuitTokenLimit;
  private final Duration retention;
  private final MultiDimensionalLimits multidimensional;
  private final Clock clock;

  @Autowired
  public StateResyncAdmissionService(
      JdbcTemplate jdbc,
      AuditApplicationService auditService,
      @Value("${state.resync.budget.window-seconds:300}") long budgetWindowSeconds,
      @Value("${state.resync.budget.session-tokens:60}") int sessionTokenLimit,
      @Value("${state.resync.budget.tenant-tokens:600}") int tenantTokenLimit,
      @Value("${state.resync.circuit.window-seconds:60}") long automaticCircuitWindowSeconds,
      @Value("${state.resync.circuit.automatic-full-tokens:30}") int automaticCircuitTokenLimit,
      @Value("${state.resync.ledger-retention-days:7}") long retentionDays,
      @Value("${state.resync.budget.session-bytes:4194304}") long sessionByteLimit,
      @Value("${state.resync.budget.tenant-bytes:67108864}") long tenantByteLimit,
      @Value("${state.resync.budget.session-cpu-millis:12000}") long sessionCpuMillisLimit,
      @Value("${state.resync.budget.tenant-cpu-millis:300000}") long tenantCpuMillisLimit,
      @Value("${state.resync.budget.node-bytes-per-vcpu:8388608}") long nodeBytesPerVcpu,
      @Value("${state.resync.budget.region-bytes-per-vcpu:33554432}") long regionBytesPerVcpu,
      @Value("${state.resync.budget.node-cpu-percent:5}") int nodeCpuBudgetPercent,
      @Value("${state.resync.budget.region-cpu-percent:5}") int regionCpuBudgetPercent,
      @Value("${state.resync.node-heartbeat-seconds:45}") long nodeHeartbeatSeconds) {
    this(
        jdbc,
        auditService,
        budgetWindowSeconds,
        sessionTokenLimit,
        tenantTokenLimit,
        automaticCircuitWindowSeconds,
        automaticCircuitTokenLimit,
        retentionDays,
        new MultiDimensionalLimits(
            sessionByteLimit,
            tenantByteLimit,
            sessionCpuMillisLimit,
            tenantCpuMillisLimit,
            nodeBytesPerVcpu,
            regionBytesPerVcpu,
            nodeCpuBudgetPercent,
            regionCpuBudgetPercent,
            Duration.ofSeconds(nodeHeartbeatSeconds)),
        Clock.systemUTC());
  }

  StateResyncAdmissionService(
      JdbcTemplate jdbc,
      AuditApplicationService auditService,
      long budgetWindowSeconds,
      int sessionTokenLimit,
      int tenantTokenLimit,
      long automaticCircuitWindowSeconds,
      int automaticCircuitTokenLimit,
      long retentionDays,
      Clock clock) {
    this(
        jdbc,
        auditService,
        budgetWindowSeconds,
        sessionTokenLimit,
        tenantTokenLimit,
        automaticCircuitWindowSeconds,
        automaticCircuitTokenLimit,
        retentionDays,
        MultiDimensionalLimits.defaults(),
        clock);
  }

  StateResyncAdmissionService(
      JdbcTemplate jdbc,
      AuditApplicationService auditService,
      long budgetWindowSeconds,
      int sessionTokenLimit,
      int tenantTokenLimit,
      long automaticCircuitWindowSeconds,
      int automaticCircuitTokenLimit,
      long retentionDays,
      MultiDimensionalLimits multidimensional,
      Clock clock) {
    requireRange(budgetWindowSeconds, 60, 3600, "State Resync budget window");
    requireRange(sessionTokenLimit, FULL_TOKEN_COST, 10_000, "Session Resync token limit");
    requireRange(tenantTokenLimit, sessionTokenLimit, 100_000, "Tenant Resync token limit");
    requireRange(automaticCircuitWindowSeconds, 30, 600, "automatic State Resync circuit window");
    requireRange(
        automaticCircuitTokenLimit,
        FULL_TOKEN_COST,
        sessionTokenLimit,
        "automatic Full Resync token limit");
    requireRange(retentionDays, 1, 90, "State Resync ledger retention");
    multidimensional.validate();
    this.jdbc = jdbc;
    this.auditService = auditService;
    this.budgetWindow = Duration.ofSeconds(budgetWindowSeconds);
    this.sessionTokenLimit = sessionTokenLimit;
    this.tenantTokenLimit = tenantTokenLimit;
    this.automaticCircuitWindow = Duration.ofSeconds(automaticCircuitWindowSeconds);
    this.automaticCircuitTokenLimit = automaticCircuitTokenLimit;
    this.retention = Duration.ofDays(retentionDays);
    this.multidimensional = multidimensional;
    this.clock = clock;
  }

  /** Admit a user request or fail with a bounded, retryable 429 domain error. */
  @Transactional
  public void admitUser(
      String tenantId,
      String sessionId,
      String actorId,
      String requestId,
      StateResyncRequest.Mode mode,
      String rootRef,
      String reason) {
    var decision =
        admit(tenantId, sessionId, requestId, mode, "USER", rootRef, reason, clock.instant());
    if (!decision.admitted()) {
      auditRejection(tenantId, sessionId, "USER", actorId, requestId, mode, decision);
      throw new StateResyncBudgetExceededException(decision.scope(), decision.retryAfterSeconds());
    }
  }

  /** Admit a workflow-owned Full Resync through the same weighted and loop budgets. */
  @Transactional
  public void admitAutomatic(
      String tenantId, String sessionId, String actorId, String requestId, String reason) {
    var decision =
        admit(
            tenantId,
            sessionId,
            requestId,
            StateResyncRequest.Mode.FULL,
            "AUTOMATIC",
            "",
            reason,
            clock.instant());
    if (!decision.admitted()) {
      auditRejection(
          tenantId,
          sessionId,
          "SYSTEM",
          actorId,
          requestId,
          StateResyncRequest.Mode.FULL,
          decision);
      throw new StateResyncBudgetExceededException(decision.scope(), decision.retryAfterSeconds());
    }
  }

  /**
   * Admit an automatic Full Resync. A denial is committed as fail-closed state (no command) rather
   * than throwing and causing the triggering Node Event to be retried forever.
   */
  @Transactional
  public AdmissionDecision tryAdmitAutomaticFull(
      String tenantId, String sessionId, String requestId, String reason) {
    return admit(
        tenantId,
        sessionId,
        requestId,
        StateResyncRequest.Mode.FULL,
        "AUTOMATIC",
        "",
        reason,
        clock.instant());
  }

  private AdmissionDecision admit(
      String tenantId,
      String sessionId,
      String requestId,
      StateResyncRequest.Mode mode,
      String source,
      String rootRef,
      String reason,
      Instant now) {
    int tokenCost = mode == StateResyncRequest.Mode.FULL ? FULL_TOKEN_COST : REGION_TOKEN_COST;
    long reservedBytes =
        mode == StateResyncRequest.Mode.FULL ? FULL_RESERVED_BYTES : REGION_RESERVED_BYTES;
    long reservedCpuMillis =
        mode == StateResyncRequest.Mode.FULL
            ? FULL_RESERVED_CPU_MILLIS
            : REGION_RESERVED_CPU_MILLIS;
    var context = loadBudgetContext(tenantId, sessionId).orElse(null);
    if (context == null || !context.available(now, multidimensional.nodeHeartbeatFreshness())) {
      return AdmissionDecision.rejected(
          "NODE_CAPACITY", Duration.ofSeconds(30), reservedCpuMillis, 0);
    }
    // Always acquire the broad lock first. This deterministic order prevents tenant/session
    // admission deadlocks across concurrent Coordinator instances.
    advisoryTransactionLock("state-resync:tenant:" + tenantId);
    advisoryTransactionLock("state-resync:region:" + context.region());
    advisoryTransactionLock("state-resync:node:" + context.nodeId());
    advisoryTransactionLock("state-resync:session:" + sessionId);
    var lockedContext = loadBudgetContext(tenantId, sessionId).orElse(null);
    if (lockedContext == null
        || !lockedContext.samePlacement(context)
        || !lockedContext.available(now, multidimensional.nodeHeartbeatFreshness())) {
      return AdmissionDecision.rejected(
          "NODE_CAPACITY", Duration.ofSeconds(30), reservedCpuMillis, 0);
    }
    context = lockedContext;
    int regionWeightPercent = context.regionWeightPercent();

    var windowStart = now.minus(budgetWindow);
    int tenantTokens = tokensForTenant(tenantId, windowStart);
    if (tenantTokens + tokenCost > tenantTokenLimit) {
      return AdmissionDecision.rejected("TENANT", budgetWindow);
    }
    int sessionTokens = tokensForSession(sessionId, windowStart);
    if (sessionTokens + tokenCost > sessionTokenLimit) {
      return AdmissionDecision.rejected("SESSION", budgetWindow);
    }
    var tenantBytes = consumedBytes("tenant_id", tenantId, windowStart);
    if (tenantBytes + reservedBytes > multidimensional.tenantByteLimit()) {
      return AdmissionDecision.rejected(
          "TENANT_BYTES",
          budgetWindow,
          reservedBytes,
          Math.max(0, multidimensional.tenantByteLimit() - tenantBytes));
    }
    var sessionBytes = consumedBytes("session_id", sessionId, windowStart);
    if (sessionBytes + reservedBytes > multidimensional.sessionByteLimit()) {
      return AdmissionDecision.rejected(
          "SESSION_BYTES",
          budgetWindow,
          reservedBytes,
          Math.max(0, multidimensional.sessionByteLimit() - sessionBytes));
    }
    var tenantCpuMillis = consumedCpuMillis("tenant_id", tenantId, windowStart);
    if (tenantCpuMillis + reservedCpuMillis > multidimensional.tenantCpuMillisLimit()) {
      return AdmissionDecision.rejected(
          "TENANT_CPU",
          budgetWindow,
          reservedCpuMillis,
          Math.max(0, multidimensional.tenantCpuMillisLimit() - tenantCpuMillis));
    }
    var sessionCpuMillis = consumedCpuMillis("session_id", sessionId, windowStart);
    if (sessionCpuMillis + reservedCpuMillis > multidimensional.sessionCpuMillisLimit()) {
      return AdmissionDecision.rejected(
          "SESSION_CPU",
          budgetWindow,
          reservedCpuMillis,
          Math.max(0, multidimensional.sessionCpuMillisLimit() - sessionCpuMillis));
    }

    long nodeByteLimit = nodeByteLimit(context.certifiedCpuMillis());
    long nodeBytes = consumedBytes("node_id", context.nodeId(), windowStart);
    if (nodeBytes + reservedBytes > nodeByteLimit) {
      return AdmissionDecision.rejected(
          "NODE_BYTES", budgetWindow, reservedBytes, Math.max(0, nodeByteLimit - nodeBytes));
    }
    long nodeCpuLimit =
        cpuWindowLimit(context.certifiedCpuMillis(), multidimensional.nodeCpuBudgetPercent(), 100);
    long nodeCpuMillis = consumedCpuMillis("node_id", context.nodeId(), windowStart);
    if (nodeCpuMillis + reservedCpuMillis > nodeCpuLimit) {
      return AdmissionDecision.rejected(
          "NODE_CPU", budgetWindow, reservedCpuMillis, Math.max(0, nodeCpuLimit - nodeCpuMillis));
    }

    long regionCertifiedCpuMillis = regionCertifiedCpuMillis(context.region(), now);
    if (regionCertifiedCpuMillis <= 0) {
      return AdmissionDecision.rejected(
          "REGION_CAPACITY", Duration.ofSeconds(30), reservedCpuMillis, 0);
    }
    long regionByteLimit = regionByteLimit(regionCertifiedCpuMillis, regionWeightPercent);
    long regionBytes = consumedBytes("region", context.region(), windowStart);
    if (regionBytes + reservedBytes > regionByteLimit) {
      return AdmissionDecision.rejected(
          "REGION_BYTES", budgetWindow, reservedBytes, Math.max(0, regionByteLimit - regionBytes));
    }
    long regionCpuLimit =
        cpuWindowLimit(
            regionCertifiedCpuMillis,
            multidimensional.regionCpuBudgetPercent(),
            regionWeightPercent);
    long regionCpuMillis = consumedCpuMillis("region", context.region(), windowStart);
    if (regionCpuMillis + reservedCpuMillis > regionCpuLimit) {
      return AdmissionDecision.rejected(
          "REGION_CPU",
          budgetWindow,
          reservedCpuMillis,
          Math.max(0, regionCpuLimit - regionCpuMillis));
    }
    if (source.equals("AUTOMATIC")) {
      int automaticTokens = automaticFullTokens(sessionId, now.minus(automaticCircuitWindow));
      if (automaticTokens + tokenCost > automaticCircuitTokenLimit) {
        return AdmissionDecision.rejected("AUTOMATIC_CIRCUIT", automaticCircuitWindow);
      }
    }

    var normalizedReason = normalizeReason(reason);
    long estimatedBytes = estimatedBytes(mode, context.currentStateBytes());
    long estimatedCpuMillis = estimatedCpuMillis(mode, estimatedBytes);
    int inserted =
        jdbc.update(
            """
            INSERT INTO state_resync_requests(
                request_id, tenant_id, session_id, mode, source, reason,
                root_ref_hash, token_cost, requested_at, node_id, region,
                region_weight_percent, estimated_bytes, reserved_bytes,
                estimated_cpu_millis, reserved_cpu_millis, budget_state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED')
            """,
            requestId,
            tenantId,
            sessionId,
            mode.name(),
            source,
            normalizedReason,
            rootRef == null || rootRef.isBlank() ? null : sha256(rootRef.trim()),
            tokenCost,
            Timestamp.from(now),
            context.nodeId(),
            context.region(),
            regionWeightPercent,
            estimatedBytes,
            reservedBytes,
            estimatedCpuMillis,
            reservedCpuMillis);
    if (inserted != 1) {
      throw new IllegalStateException("State Resync admission was not persisted");
    }
    return AdmissionDecision.accepted(reservedBytes, reservedCpuMillis);
  }

  private void auditRejection(
      String tenantId,
      String sessionId,
      String actorType,
      String actorId,
      String requestId,
      StateResyncRequest.Mode mode,
      AdmissionDecision decision) {
    auditService.appendIndependent(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "STATE_RESYNC_BUDGET_REJECTED",
            actorType,
            actorId,
            "SESSION",
            sessionId,
            "REQUEST_" + mode.name() + "_RESYNC",
            "BLOCKED",
            Map.of(
                "scope",
                decision.scope(),
                "retryAfterSeconds",
                decision.retryAfterSeconds(),
                "requestedUnits",
                decision.requestedUnits(),
                "availableUnits",
                decision.availableUnits()),
            requestId));
  }

  private int tokensForTenant(String tenantId, Instant since) {
    return jdbc.queryForObject(
        """
        SELECT COALESCE(SUM(token_cost), 0)
        FROM state_resync_requests
        WHERE tenant_id = ? AND requested_at >= ?
        """,
        Integer.class,
        tenantId,
        Timestamp.from(since));
  }

  private int tokensForSession(String sessionId, Instant since) {
    return jdbc.queryForObject(
        """
        SELECT COALESCE(SUM(token_cost), 0)
        FROM state_resync_requests
        WHERE session_id = ? AND requested_at >= ?
        """,
        Integer.class,
        sessionId,
        Timestamp.from(since));
  }

  private int automaticFullTokens(String sessionId, Instant since) {
    return jdbc.queryForObject(
        """
        SELECT COALESCE(SUM(token_cost), 0)
        FROM state_resync_requests
        WHERE session_id = ? AND requested_at >= ?
          AND source = 'AUTOMATIC' AND mode = 'FULL'
        """,
        Integer.class,
        sessionId,
        Timestamp.from(since));
  }

  Optional<BudgetContext> loadBudgetContext(String tenantId, String sessionId) {
    return jdbc.query(
        """
        SELECT placement.node_id,
               node.region,
               node.certified_cpu_millis,
               node.lifecycle_state,
               node.admission_state,
               node.pressure_state,
               node.last_heartbeat_at,
               COALESCE(octet_length(state.state_json::text), 4096) AS current_state_bytes,
               COALESCE(region.role, 'PRIMARY') AS region_role,
               COALESCE(region.admission_state, 'OPEN') AS region_admission_state
          FROM browser_placements placement
          JOIN browser_nodes node ON node.node_id = placement.node_id
          LEFT JOIN browser_states state ON state.session_id = placement.session_id
          LEFT JOIN enterprise_regions region ON region.region_id = node.region
         WHERE placement.session_id = ?
           AND placement.tenant_id = ?
           AND placement.state IN ('RESERVED', 'ACTIVE')
         LIMIT 1
        """,
        resultSet -> resultSet.next() ? Optional.of(budgetContext(resultSet)) : Optional.empty(),
        sessionId,
        tenantId);
  }

  private BudgetContext budgetContext(ResultSet resultSet) throws SQLException {
    return new BudgetContext(
        resultSet.getString("node_id"),
        resultSet.getString("region"),
        resultSet.getLong("certified_cpu_millis"),
        resultSet.getString("lifecycle_state"),
        resultSet.getString("admission_state"),
        resultSet.getString("pressure_state"),
        resultSet.getTimestamp("last_heartbeat_at").toInstant(),
        resultSet.getLong("current_state_bytes"),
        resultSet.getString("region_role"),
        resultSet.getString("region_admission_state"));
  }

  private long consumedBytes(String dimension, String value, Instant since) {
    var column = budgetColumn(dimension);
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(COALESCE(actual_bytes, reserved_bytes)), 0) "
            + "FROM state_resync_requests WHERE "
            + column
            + " = ? AND requested_at >= ?",
        Long.class,
        value,
        Timestamp.from(since));
  }

  private long consumedCpuMillis(String dimension, String value, Instant since) {
    var column = budgetColumn(dimension);
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(COALESCE(actual_cpu_millis, reserved_cpu_millis)), 0) "
            + "FROM state_resync_requests WHERE "
            + column
            + " = ? AND requested_at >= ?",
        Long.class,
        value,
        Timestamp.from(since));
  }

  private String budgetColumn(String dimension) {
    return switch (dimension) {
      case "tenant_id", "session_id", "region", "node_id" -> dimension;
      default -> throw new IllegalArgumentException("unsupported Resync budget dimension");
    };
  }

  private long regionCertifiedCpuMillis(String region, Instant now) {
    return jdbc.queryForObject(
        """
        SELECT COALESCE(SUM(certified_cpu_millis), 0)
          FROM browser_nodes
         WHERE region = ?
           AND lifecycle_state = 'READY'
           AND admission_state = 'OPEN'
           AND pressure_state = 'NORMAL'
           AND last_heartbeat_at >= ?
        """,
        Long.class,
        region,
        Timestamp.from(now.minus(multidimensional.nodeHeartbeatFreshness())));
  }

  private long nodeByteLimit(long certifiedCpuMillis) {
    long virtualCpus = Math.max(1, (certifiedCpuMillis + 999) / 1_000);
    return Math.multiplyExact(virtualCpus, multidimensional.nodeBytesPerVcpu());
  }

  private long regionByteLimit(long certifiedCpuMillis, int regionWeightPercent) {
    long virtualCpus = Math.max(1, (certifiedCpuMillis + 999) / 1_000);
    return Math.multiplyExact(virtualCpus, multidimensional.regionBytesPerVcpu())
        * regionWeightPercent
        / 100;
  }

  private long cpuWindowLimit(
      long certifiedCpuMillis, int budgetPercent, int capacityWeightPercent) {
    return certifiedCpuMillis
        * budgetWindow.toSeconds()
        * budgetPercent
        * capacityWeightPercent
        / 10_000;
  }

  private long estimatedBytes(StateResyncRequest.Mode mode, long currentStateBytes) {
    long boundedState = Math.max(4_096, Math.min(FULL_RESERVED_BYTES, currentStateBytes));
    return mode == StateResyncRequest.Mode.FULL
        ? Math.min(FULL_RESERVED_BYTES, boundedState + boundedState / 4)
        : Math.min(REGION_RESERVED_BYTES, Math.max(4_096, boundedState / 4));
  }

  private long estimatedCpuMillis(StateResyncRequest.Mode mode, long estimatedBytes) {
    long perKib = (estimatedBytes + 1_023) / 1_024;
    return mode == StateResyncRequest.Mode.FULL
        ? Math.min(FULL_RESERVED_CPU_MILLIS, 100 + perKib * 2)
        : Math.min(REGION_RESERVED_CPU_MILLIS, 25 + perKib);
  }

  /** Replace admission reservations with measured Snapshot bytes and cgroup CPU time. */
  @Transactional
  public void settleActual(
      String tenantId,
      String sessionId,
      String requestId,
      StateResyncRequest.Mode mode,
      long actualBytes,
      Long actualCpuMillis) {
    long maximumBytes =
        mode == StateResyncRequest.Mode.FULL ? FULL_RESERVED_BYTES : REGION_RESERVED_BYTES;
    if (actualBytes <= 0 || actualBytes > maximumBytes) {
      throw new IllegalArgumentException(
          "State Resync actual bytes exceed the bounded reservation");
    }
    if (actualCpuMillis != null && (actualCpuMillis < 0 || actualCpuMillis > 300_000)) {
      throw new IllegalArgumentException("State Resync actual CPU is outside the bounded range");
    }
    var ledger = loadLedgerContext(requestId).orElse(null);
    if (ledger == null) {
      return;
    }
    if (!ledger.tenantId().equals(tenantId)
        || !ledger.sessionId().equals(sessionId)
        || !ledger.mode().equals(mode.name())) {
      throw new IllegalArgumentException("State Resync settlement does not match its reservation");
    }
    advisoryTransactionLock("state-resync:tenant:" + tenantId);
    if (ledger.region() != null) {
      advisoryTransactionLock("state-resync:region:" + ledger.region());
    }
    if (ledger.nodeId() != null) {
      advisoryTransactionLock("state-resync:node:" + ledger.nodeId());
    }
    advisoryTransactionLock("state-resync:session:" + sessionId);
    int settled =
        jdbc.update(
            """
            UPDATE state_resync_requests
               SET actual_bytes = ?, actual_cpu_millis = ?,
                   budget_state = 'SETTLED', settled_at = ?
             WHERE request_id = ? AND budget_state = 'RESERVED'
            """,
            actualBytes,
            actualCpuMillis,
            Timestamp.from(clock.instant()),
            requestId);
    if (settled == 1) {
      auditService.append(
          new AuditApplicationService.AuditRecord(
              tenantId,
              sessionId,
              "STATE_RESYNC_BUDGET_SETTLED",
              "NODE",
              ledger.nodeId() == null ? "node-event-stream" : ledger.nodeId(),
              "SESSION",
              sessionId,
              "SETTLE_" + mode.name() + "_RESYNC",
              "COMMITTED",
              Map.of(
                  "actualBytes",
                  actualBytes,
                  "actualCpuMillis",
                  actualCpuMillis == null ? ledger.reservedCpuMillis() : actualCpuMillis,
                  "cpuMeasurement",
                  actualCpuMillis == null ? "RESERVED_FALLBACK" : "BROWSER_CGROUP",
                  "nodeId",
                  ledger.nodeId() == null ? "unknown" : ledger.nodeId(),
                  "region",
                  ledger.region() == null ? "unknown" : ledger.region()),
              requestId));
    }
  }

  Optional<LedgerContext> loadLedgerContext(String requestId) {
    return jdbc.query(
        """
        SELECT tenant_id, session_id, mode, node_id, region, reserved_cpu_millis
          FROM state_resync_requests
         WHERE request_id = ?
        """,
        resultSet ->
            resultSet.next()
                ? Optional.of(
                    new LedgerContext(
                        resultSet.getString("tenant_id"),
                        resultSet.getString("session_id"),
                        resultSet.getString("mode"),
                        resultSet.getString("node_id"),
                        resultSet.getString("region"),
                        resultSet.getLong("reserved_cpu_millis")))
                : Optional.empty(),
        requestId);
  }

  private void advisoryTransactionLock(String key) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
        (RowCallbackHandler) resultSet -> {},
        key);
  }

  @Scheduled(cron = "${state.resync.cleanup-cron:0 41 3 * * *}")
  @Transactional
  public void deleteExpiredLedger() {
    jdbc.update(
        "DELETE FROM state_resync_requests WHERE requested_at < ?",
        Timestamp.from(clock.instant().minus(retention)));
  }

  private static String normalizeReason(String reason) {
    var normalized = reason == null || reason.isBlank() ? "UNSPECIFIED" : reason.trim();
    if (normalized.length() > 128 || normalized.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("State Resync reason is outside the bounded format");
    }
    return normalized;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void requireRange(long value, long minimum, long maximum, String name) {
    if (value < minimum || value > maximum) {
      throw new IllegalStateException(name + " must be between " + minimum + " and " + maximum);
    }
  }

  record BudgetContext(
      String nodeId,
      String region,
      long certifiedCpuMillis,
      String lifecycleState,
      String admissionState,
      String pressureState,
      Instant lastHeartbeatAt,
      long currentStateBytes,
      String regionRole,
      String regionAdmissionState) {
    boolean available(Instant now, Duration freshness) {
      return lifecycleState.equals("READY")
          && admissionState.equals("OPEN")
          && pressureState.equals("NORMAL")
          && !regionAdmissionState.equals("CLOSED")
          && !lastHeartbeatAt.isBefore(now.minus(freshness));
    }

    int regionWeightPercent() {
      return switch (regionRole) {
        case "DR" -> 50;
        case "SECONDARY" -> 75;
        default -> 100;
      };
    }

    boolean samePlacement(BudgetContext other) {
      return nodeId.equals(other.nodeId) && region.equals(other.region);
    }
  }

  record LedgerContext(
      String tenantId,
      String sessionId,
      String mode,
      String nodeId,
      String region,
      long reservedCpuMillis) {}

  record MultiDimensionalLimits(
      long sessionByteLimit,
      long tenantByteLimit,
      long sessionCpuMillisLimit,
      long tenantCpuMillisLimit,
      long nodeBytesPerVcpu,
      long regionBytesPerVcpu,
      int nodeCpuBudgetPercent,
      int regionCpuBudgetPercent,
      Duration nodeHeartbeatFreshness) {
    static MultiDimensionalLimits defaults() {
      return new MultiDimensionalLimits(
          4L * 1024 * 1024,
          64L * 1024 * 1024,
          12_000,
          300_000,
          8L * 1024 * 1024,
          32L * 1024 * 1024,
          5,
          5,
          Duration.ofSeconds(45));
    }

    void validate() {
      requireRange(sessionByteLimit, FULL_RESERVED_BYTES, 1L << 30, "Session Resync byte limit");
      requireRange(tenantByteLimit, sessionByteLimit, 1L << 34, "Tenant Resync byte limit");
      requireRange(
          sessionCpuMillisLimit, FULL_RESERVED_CPU_MILLIS, 3_600_000, "Session Resync CPU limit");
      requireRange(
          tenantCpuMillisLimit, sessionCpuMillisLimit, 86_400_000, "Tenant Resync CPU limit");
      requireRange(nodeBytesPerVcpu, FULL_RESERVED_BYTES, 1L << 30, "Node bytes per vCPU");
      requireRange(regionBytesPerVcpu, nodeBytesPerVcpu, 1L << 32, "Region bytes per vCPU");
      requireRange(nodeCpuBudgetPercent, 1, 25, "Node Resync CPU percent");
      requireRange(regionCpuBudgetPercent, 1, 25, "Region Resync CPU percent");
      requireRange(nodeHeartbeatFreshness.toSeconds(), 10, 300, "Node heartbeat freshness");
    }
  }

  public record AdmissionDecision(
      boolean admitted,
      String scope,
      int retryAfterSeconds,
      long requestedUnits,
      long availableUnits) {
    public AdmissionDecision(boolean admitted, String scope, int retryAfterSeconds) {
      this(admitted, scope, retryAfterSeconds, 0, 0);
    }

    static AdmissionDecision accepted(long reservedBytes, long reservedCpuMillis) {
      return new AdmissionDecision(true, "", 0, reservedBytes, reservedCpuMillis);
    }

    static AdmissionDecision rejected(String scope, Duration retryAfter) {
      return rejected(scope, retryAfter, 0, 0);
    }

    static AdmissionDecision rejected(
        String scope, Duration retryAfter, long requestedUnits, long availableUnits) {
      return new AdmissionDecision(
          false, scope, Math.toIntExact(retryAfter.toSeconds()), requestedUnits, availableUnits);
    }
  }

  public static final class StateResyncBudgetExceededException extends RuntimeException {
    private final String scope;
    private final int retryAfterSeconds;

    public StateResyncBudgetExceededException(String scope, int retryAfterSeconds) {
      super("State Resync " + scope + " budget is exhausted");
      this.scope = scope;
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public String scope() {
      return scope;
    }

    public int retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }
}
