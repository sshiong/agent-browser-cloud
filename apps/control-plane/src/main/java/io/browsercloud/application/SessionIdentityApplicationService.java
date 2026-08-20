package io.browsercloud.application;

import static io.browsercloud.api.SessionIdentityModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.session.SessionState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL authority for creation-time locked Browser identity and approved changes. */
@Service
public class SessionIdentityApplicationService {

  private static final SessionIdentitySpecRequest DEFAULT_SPEC =
      new SessionIdentitySpecRequest(
          null,
          null,
          null,
          java.util.List.of(),
          WebRtcPolicy.DEFAULT,
          DnsPolicy.SYSTEM,
          null,
          null,
          null,
          null,
          null,
          null,
          null);

  private final JdbcTemplate jdbc;
  private final SessionRepository sessions;
  private final AuditApplicationService audit;
  private final ObjectMapper objectMapper;

  public SessionIdentityApplicationService(
      JdbcTemplate jdbc,
      SessionRepository sessions,
      AuditApplicationService audit,
      ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.sessions = sessions;
    this.audit = audit;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public void initialize(
      String sessionId, String tenantId, SessionIdentitySpecRequest requested, Instant now) {
    var spec = normalize(requested);
    var json = write(spec);
    if (jdbc.update(
            """
            INSERT INTO session_identity_specs(
              session_id, tenant_id, spec_json, spec_hash, version, locked_at, updated_at
            ) VALUES (?, ?, ?::jsonb, ?, 1, ?, ?)
            ON CONFLICT (session_id) DO NOTHING
            """,
            sessionId,
            tenantId,
            json,
            sha256(json),
            timestamp(now),
            timestamp(now))
        != 1) {
      throw new SessionIdentityRejectedException("SESSION_IDENTITY_ALREADY_INITIALIZED");
    }
  }

  @Transactional(readOnly = true)
  public SessionIdentitySpecView get(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    return jdbc.queryForObject(
        "SELECT * FROM session_identity_specs WHERE session_id=? AND tenant_id=?",
        (result, row) ->
            new SessionIdentitySpecView(
                result.getString("session_id"),
                result.getLong("version"),
                result.getString("spec_hash"),
                true,
                read(result.getString("spec_json")),
                result.getTimestamp("locked_at").toInstant(),
                result.getTimestamp("updated_at").toInstant()),
        sessionId,
        tenantId);
  }

  public void rejectDirectMutation(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    throw new SessionIdentityRejectedException("SESSION_CONFIG_LOCKED");
  }

  @Transactional
  public SessionIdentityChangeRequestView requestChange(
      String sessionId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      CreateSessionIdentityChangeRequest request) {
    requireTenant(sessionId, tenantId);
    var proposed = normalize(request.proposedSpec());
    var json = write(proposed);
    var hash = sha256(json);
    var existing = findByIdempotency(sessionId, tenantId, idempotencyKey);
    if (existing != null) {
      if (!existing.proposedSpecHash().equals(hash)
          || existing.expectedVersion() != request.expectedVersion()
          || !existing.reason().equals(request.reason())) {
        throw new SessionIdentityRejectedException("IDEMPOTENCY_CONFLICT");
      }
      return existing;
    }
    var current = lockSpec(sessionId, tenantId);
    if (current.version() != request.expectedVersion()) {
      throw new SessionIdentityRejectedException("SESSION_CONFIG_VERSION_MISMATCH");
    }
    if (current.specHash().equals(hash)) {
      throw new SessionIdentityRejectedException("SESSION_CONFIG_UNCHANGED");
    }
    var requestId = "sicr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    var now = Instant.now();
    jdbc.update(
        """
        INSERT INTO session_identity_change_requests(
          request_id, tenant_id, session_id, expected_version, proposed_spec_json,
          proposed_spec_hash, reason, idempotency_key, state, created_by, created_at
        ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'PENDING', ?, ?)
        """,
        requestId,
        tenantId,
        sessionId,
        request.expectedVersion(),
        json,
        hash,
        request.reason(),
        idempotencyKey,
        actorId,
        timestamp(now));
    appendAudit(tenantId, sessionId, requestId, actorId, "REQUEST", "PENDING", hash);
    return requireChange(requestId, tenantId, false);
  }

  @Transactional
  public SessionIdentityChangeRequestView decide(
      String requestId, String tenantId, String actorId, boolean approve) {
    var change = requireChange(requestId, tenantId, true);
    if (change.state() != ChangeState.PENDING) return change;
    var current = lockSpec(change.sessionId(), tenantId);
    var state = approve && current.version() == change.expectedVersion() ? "APPROVED" : "REJECTED";
    var now = Instant.now();
    jdbc.update(
        """
        UPDATE session_identity_change_requests
        SET state=?, decided_by=?, decided_at=?
        WHERE request_id=? AND tenant_id=? AND state='PENDING'
        """,
        state,
        actorId,
        timestamp(now),
        requestId,
        tenantId);
    appendAudit(
        tenantId,
        change.sessionId(),
        requestId,
        actorId,
        approve ? "APPROVE" : "REJECT",
        state,
        change.proposedSpecHash());
    return requireChange(requestId, tenantId, false);
  }

  @Transactional
  public SessionIdentityChangeRequestView apply(String requestId, String tenantId, String actorId) {
    var change = requireChange(requestId, tenantId, true);
    if (change.state() == ChangeState.APPLIED) return change;
    if (change.state() != ChangeState.APPROVED) {
      throw new SessionIdentityRejectedException("SESSION_CONFIG_CHANGE_NOT_APPROVED");
    }
    var session = sessions.require(change.sessionId());
    if (!session.tenantId().equals(tenantId)) throw new TenantAccessDeniedException(requestId);
    if (!java.util.Set.of(SessionState.CREATED, SessionState.HIBERNATED)
        .contains(session.state())) {
      throw new SessionIdentityRejectedException("SESSION_CONFIG_SAFE_RESTART_REQUIRED");
    }
    var current = lockSpec(change.sessionId(), tenantId);
    if (current.version() != change.expectedVersion()) {
      jdbc.update(
          "UPDATE session_identity_change_requests SET state='STALE', decided_by=?, decided_at=COALESCE(decided_at, ?) WHERE request_id=?",
          actorId,
          timestamp(Instant.now()),
          requestId);
      throw new SessionIdentityRejectedException("SESSION_CONFIG_VERSION_MISMATCH");
    }
    var now = Instant.now();
    jdbc.update(
        """
        UPDATE session_identity_specs
        SET spec_json=?::jsonb, spec_hash=?, version=version+1, updated_at=?
        WHERE session_id=? AND tenant_id=? AND version=?
        """,
        write(change.proposedSpec()),
        change.proposedSpecHash(),
        timestamp(now),
        change.sessionId(),
        tenantId,
        change.expectedVersion());
    jdbc.update(
        "UPDATE session_identity_change_requests SET state='APPLIED', applied_at=? WHERE request_id=?",
        timestamp(now),
        requestId);
    appendAudit(
        tenantId,
        change.sessionId(),
        requestId,
        actorId,
        "APPLY",
        "APPLIED",
        change.proposedSpecHash());
    return requireChange(requestId, tenantId, false);
  }

  private SessionIdentitySpecView lockSpec(String sessionId, String tenantId) {
    return jdbc.queryForObject(
        "SELECT * FROM session_identity_specs WHERE session_id=? AND tenant_id=? FOR UPDATE",
        (result, row) ->
            new SessionIdentitySpecView(
                sessionId,
                result.getLong("version"),
                result.getString("spec_hash"),
                true,
                read(result.getString("spec_json")),
                result.getTimestamp("locked_at").toInstant(),
                result.getTimestamp("updated_at").toInstant()),
        sessionId,
        tenantId);
  }

  private SessionIdentityChangeRequestView findByIdempotency(
      String sessionId, String tenantId, String idempotencyKey) {
    return jdbc
        .query(
            "SELECT * FROM session_identity_change_requests WHERE session_id=? AND tenant_id=? AND idempotency_key=?",
            (result, row) -> change(result),
            sessionId,
            tenantId,
            idempotencyKey)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private SessionIdentityChangeRequestView requireChange(
      String requestId, String tenantId, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM session_identity_change_requests WHERE request_id=? AND tenant_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (result, row) -> change(result),
            requestId,
            tenantId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new SessionIdentityRejectedException("SESSION_CONFIG_CHANGE_NOT_FOUND"));
  }

  private SessionIdentityChangeRequestView change(java.sql.ResultSet result)
      throws java.sql.SQLException {
    return new SessionIdentityChangeRequestView(
        result.getString("request_id"),
        result.getString("session_id"),
        result.getLong("expected_version"),
        result.getString("proposed_spec_hash"),
        read(result.getString("proposed_spec_json")),
        result.getString("reason"),
        ChangeState.valueOf(result.getString("state")),
        result.getString("created_by"),
        result.getString("decided_by"),
        result.getTimestamp("created_at").toInstant(),
        instant(result.getTimestamp("decided_at")),
        instant(result.getTimestamp("applied_at")));
  }

  private void requireTenant(String sessionId, String tenantId) {
    if (!sessions.require(sessionId).tenantId().equals(tenantId)) {
      throw new TenantAccessDeniedException(sessionId);
    }
  }

  private void appendAudit(
      String tenantId,
      String sessionId,
      String requestId,
      String actorId,
      String action,
      String result,
      String specHash) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "SESSION_IDENTITY_CONFIG_CHANGE",
            "USER",
            actorId,
            "SESSION_IDENTITY_CHANGE_REQUEST",
            requestId,
            action,
            result,
            Map.of("specHash", specHash, "directMutation", false),
            "identity-change:" + requestId + ":" + action));
  }

  private SessionIdentitySpecRequest normalize(SessionIdentitySpecRequest request) {
    return request == null
        ? DEFAULT_SPEC
        : new SessionIdentitySpecRequest(
            blankToNull(request.userAgent()),
            blankToNull(request.timezone()),
            blankToNull(request.locale()),
            request.languages().stream().distinct().toList(),
            request.webRtcPolicy() == null ? WebRtcPolicy.DEFAULT : request.webRtcPolicy(),
            request.dnsPolicy() == null ? DnsPolicy.SYSTEM : request.dnsPolicy(),
            request.viewportWidth(),
            request.viewportHeight(),
            request.screenWidth(),
            request.screenHeight(),
            request.deviceScaleFactor(),
            blankToNull(request.fingerprintProfile()),
            blankToNull(request.operatingSystemProfile()));
  }

  private String write(SessionIdentitySpecRequest spec) {
    try {
      return objectMapper.writeValueAsString(spec);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Session identity spec is not serializable", exception);
    }
  }

  private SessionIdentitySpecRequest read(String json) {
    try {
      return objectMapper.readValue(json, SessionIdentitySpecRequest.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Session identity spec is invalid", exception);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static java.sql.Timestamp timestamp(Instant value) {
    return java.sql.Timestamp.from(value);
  }

  private static Instant instant(java.sql.Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  public static final class SessionIdentityRejectedException extends RuntimeException {
    public SessionIdentityRejectedException(String message) {
      super(message);
    }
  }
}
