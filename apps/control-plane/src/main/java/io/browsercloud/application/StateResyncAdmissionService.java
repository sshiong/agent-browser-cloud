package io.browsercloud.application;

import io.browsercloud.api.StateResyncRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
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

  private final JdbcTemplate jdbc;
  private final AuditApplicationService auditService;
  private final Duration budgetWindow;
  private final int sessionTokenLimit;
  private final int tenantTokenLimit;
  private final Duration automaticCircuitWindow;
  private final int automaticCircuitTokenLimit;
  private final Duration retention;
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
      @Value("${state.resync.ledger-retention-days:7}") long retentionDays) {
    this(
        jdbc,
        auditService,
        budgetWindowSeconds,
        sessionTokenLimit,
        tenantTokenLimit,
        automaticCircuitWindowSeconds,
        automaticCircuitTokenLimit,
        retentionDays,
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
    this.jdbc = jdbc;
    this.auditService = auditService;
    this.budgetWindow = Duration.ofSeconds(budgetWindowSeconds);
    this.sessionTokenLimit = sessionTokenLimit;
    this.tenantTokenLimit = tenantTokenLimit;
    this.automaticCircuitWindow = Duration.ofSeconds(automaticCircuitWindowSeconds);
    this.automaticCircuitTokenLimit = automaticCircuitTokenLimit;
    this.retention = Duration.ofDays(retentionDays);
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
    // Always acquire the broad lock first. This deterministic order prevents tenant/session
    // admission deadlocks across concurrent Coordinator instances.
    advisoryTransactionLock("state-resync:tenant:" + tenantId);
    advisoryTransactionLock("state-resync:session:" + sessionId);

    var windowStart = now.minus(budgetWindow);
    int tenantTokens = tokensForTenant(tenantId, windowStart);
    if (tenantTokens + tokenCost > tenantTokenLimit) {
      return AdmissionDecision.rejected("TENANT", budgetWindow);
    }
    int sessionTokens = tokensForSession(sessionId, windowStart);
    if (sessionTokens + tokenCost > sessionTokenLimit) {
      return AdmissionDecision.rejected("SESSION", budgetWindow);
    }
    if (source.equals("AUTOMATIC")) {
      int automaticTokens = automaticFullTokens(sessionId, now.minus(automaticCircuitWindow));
      if (automaticTokens + tokenCost > automaticCircuitTokenLimit) {
        return AdmissionDecision.rejected("AUTOMATIC_CIRCUIT", automaticCircuitWindow);
      }
    }

    var normalizedReason = normalizeReason(reason);
    int inserted =
        jdbc.update(
            """
            INSERT INTO state_resync_requests(
                request_id, tenant_id, session_id, mode, source, reason,
                root_ref_hash, token_cost, requested_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            requestId,
            tenantId,
            sessionId,
            mode.name(),
            source,
            normalizedReason,
            rootRef == null || rootRef.isBlank() ? null : sha256(rootRef.trim()),
            tokenCost,
            Timestamp.from(now));
    if (inserted != 1) {
      throw new IllegalStateException("State Resync admission was not persisted");
    }
    return AdmissionDecision.accepted();
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
            Map.of("scope", decision.scope(), "retryAfterSeconds", decision.retryAfterSeconds()),
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

  public record AdmissionDecision(boolean admitted, String scope, int retryAfterSeconds) {
    static AdmissionDecision accepted() {
      return new AdmissionDecision(true, "", 0);
    }

    static AdmissionDecision rejected(String scope, Duration retryAfter) {
      return new AdmissionDecision(false, scope, Math.toIntExact(retryAfter.toSeconds()));
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
