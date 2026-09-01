package io.browsercloud.application;

import static io.browsercloud.api.AgentClipboardBridgeModels.*;

import io.browsercloud.api.AgentClipboardModels.WriteAgentClipboardRequest;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.RemoteDesktopParticipantJpaRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-authoritative, client-mediated bridge across the isolated clipboard domains. */
@Service
public class AgentClipboardBridgeApplicationService {
  static final Duration BRIDGE_TTL = Duration.ofSeconds(60);
  static final Duration USER_CLIPBOARD_MAX_AGE = Duration.ofMinutes(2);
  private final JdbcTemplate jdbc;
  private final SessionRepository sessions;
  private final RemoteDesktopParticipantJpaRepository participants;
  private final AgentClipboardApplicationService clipboard;
  private final AgentActionPayloadService payloads;
  private final AuditApplicationService audit;

  public AgentClipboardBridgeApplicationService(
      JdbcTemplate jdbc,
      SessionRepository sessions,
      RemoteDesktopParticipantJpaRepository participants,
      AgentClipboardApplicationService clipboard,
      AgentActionPayloadService payloads,
      AuditApplicationService audit) {
    this.jdbc = jdbc;
    this.sessions = sessions;
    this.participants = participants;
    this.clipboard = clipboard;
    this.payloads = payloads;
    this.audit = audit;
  }

  @Transactional
  public ClipboardBridgeView create(
      String sessionId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      CreateClipboardBridgeRequest request) {
    var session = sessions.requireForUpdate(sessionId);
    requireTenant(session.tenantId(), tenantId, sessionId);
    if (session.state() != SessionState.RUNNING && session.state() != SessionState.DEGRADED) {
      throw rejected("SESSION_NOT_RUNNING");
    }
    var requestHash = requestHash(sessionId, request);
    var existing = findByIdempotency(tenantId, actorId, idempotencyKey);
    if (existing != null) {
      if (!existing.sessionId().equals(sessionId) || !existing.requestHash().equals(requestHash)) {
        throw rejected("CLIPBOARD_BRIDGE_IDEMPOTENCY_CONFLICT");
      }
      return replay(existing, tenantId, sessionId, actorId, requestId);
    }

    var participant =
        participants
            .findForUpdate(request.connectionId(), tenantId, sessionId)
            .orElseThrow(() -> rejected("CLIPBOARD_BRIDGE_CONNECTION_NOT_FOUND"));
    if (!"CONNECTED".equals(participant.getState())
        || participant.getContextEpoch() != session.contextEpoch()
        || !actorId.equals(participant.getActorId())) {
      throw rejected("CLIPBOARD_BRIDGE_CONNECTION_NOT_ACTIVE");
    }
    if (request.direction() == ClipboardBridgeDirection.AGENT_TO_USER
        && Boolean.TRUE.equals(participant.getViewOnly())) {
      throw rejected("CLIPBOARD_BRIDGE_CONTROL_CONNECTION_REQUIRED");
    }

    var now = Instant.now();
    String value;
    long version;
    String contentHash;
    if (request.direction() == ClipboardBridgeDirection.USER_TO_AGENT) {
      requireFreshUserClipboard(request.userClipboardObservedAt(), now);
      var written =
          clipboard.write(
              sessionId,
              tenantId,
              actorId,
              new WriteAgentClipboardRequest(
                  request.value(), request.expectedAgentClipboardVersion()));
      value = null;
      version = written.version();
      contentHash = written.contentHash();
    } else {
      var source = clipboard.read(sessionId, tenantId, actorId);
      if (source.value() == null || source.valueLength() == 0) {
        throw rejected("AGENT_CLIPBOARD_EMPTY");
      }
      if (source.version() != request.expectedAgentClipboardVersion()) {
        throw rejected("AGENT_CLIPBOARD_VERSION_MISMATCH");
      }
      value = source.value();
      version = source.version();
      contentHash = source.contentHash();
    }

    var bridgeId = id("acb_", 20);
    var state =
        request.direction() == ClipboardBridgeDirection.USER_TO_AGENT ? "COMPLETED" : "ISSUED";
    var expiresAt = now.plus(BRIDGE_TTL);
    jdbc.update(
        """
        INSERT INTO agent_clipboard_bridges(
          bridge_id, tenant_id, session_id, actor_id, connection_id, direction, purpose,
          idempotency_key, request_hash, agent_clipboard_version, content_hash, value_length,
          state, expires_at, completed_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        bridgeId,
        tenantId,
        sessionId,
        actorId,
        request.connectionId(),
        request.direction().name(),
        request.purpose().name(),
        idempotencyKey,
        requestHash,
        version,
        contentHash,
        request.direction() == ClipboardBridgeDirection.USER_TO_AGENT
            ? request.value().length()
            : value.length(),
        state,
        timestamp(expiresAt),
        "COMPLETED".equals(state) ? timestamp(now) : null,
        timestamp(now),
        timestamp(now));
    var row = find(bridgeId, tenantId, sessionId, actorId);
    appendAudit(row, actorId, requestId, "CREATE", state);
    return view(row, value);
  }

  @Transactional
  public ClipboardBridgeView complete(
      String sessionId,
      String bridgeId,
      String tenantId,
      String actorId,
      String requestId,
      CompleteClipboardBridgeRequest request) {
    var session = sessions.requireForUpdate(sessionId);
    requireTenant(session.tenantId(), tenantId, sessionId);
    var row = findForUpdate(bridgeId, tenantId, sessionId, actorId);
    if (row.direction() != ClipboardBridgeDirection.AGENT_TO_USER) {
      throw rejected("CLIPBOARD_BRIDGE_ALREADY_COMPLETED");
    }
    if (!MessageHashes.equal(row.contentHash(), request.contentHash())) {
      throw rejected("CLIPBOARD_BRIDGE_CONTENT_MISMATCH");
    }
    if ("COMPLETED".equals(row.state())) return view(row, null);
    if (!"ISSUED".equals(row.state()) || !Instant.now().isBefore(row.expiresAt())) {
      expire(row.bridgeId(), tenantId, sessionId, actorId);
      var expired = find(bridgeId, tenantId, sessionId, actorId);
      appendAudit(expired, actorId, requestId, "EXPIRE", "EXPIRED");
      return view(expired, null);
    }
    var now = Instant.now();
    jdbc.update(
        "UPDATE agent_clipboard_bridges SET state='COMPLETED', completed_at=?, updated_at=? WHERE bridge_id=? AND tenant_id=? AND session_id=? AND actor_id=? AND state='ISSUED'",
        timestamp(now),
        timestamp(now),
        bridgeId,
        tenantId,
        sessionId,
        actorId);
    var completed = find(bridgeId, tenantId, sessionId, actorId);
    appendAudit(completed, actorId, requestId, "COMPLETE", "COMPLETED");
    return view(completed, null);
  }

  private ClipboardBridgeView replay(
      BridgeRow row, String tenantId, String sessionId, String actorId, String requestId) {
    if ("ISSUED".equals(row.state()) && !Instant.now().isBefore(row.expiresAt())) {
      expire(row.bridgeId(), tenantId, sessionId, actorId);
      var expired = find(row.bridgeId(), tenantId, sessionId, actorId);
      appendAudit(expired, actorId, requestId, "EXPIRE", "EXPIRED");
      return view(expired, null);
    }
    String value = null;
    if (row.direction() == ClipboardBridgeDirection.AGENT_TO_USER && "ISSUED".equals(row.state())) {
      var source = clipboard.read(sessionId, tenantId, actorId);
      if (source.version() != row.agentClipboardVersion()
          || !MessageHashes.equal(source.contentHash(), row.contentHash())) {
        throw rejected("CLIPBOARD_BRIDGE_SOURCE_CHANGED");
      }
      value = source.value();
    }
    return view(row, value);
  }

  private BridgeRow findByIdempotency(String tenantId, String actorId, String idempotencyKey) {
    return query(
        "SELECT * FROM agent_clipboard_bridges WHERE tenant_id=? AND actor_id=? AND idempotency_key=?",
        tenantId,
        actorId,
        idempotencyKey);
  }

  private BridgeRow find(String bridgeId, String tenantId, String sessionId, String actorId) {
    var row =
        query(
            "SELECT * FROM agent_clipboard_bridges WHERE bridge_id=? AND tenant_id=? AND session_id=? AND actor_id=?",
            bridgeId,
            tenantId,
            sessionId,
            actorId);
    if (row == null) throw rejected("CLIPBOARD_BRIDGE_NOT_FOUND");
    return row;
  }

  private BridgeRow findForUpdate(
      String bridgeId, String tenantId, String sessionId, String actorId) {
    var row =
        query(
            "SELECT * FROM agent_clipboard_bridges WHERE bridge_id=? AND tenant_id=? AND session_id=? AND actor_id=? FOR UPDATE",
            bridgeId,
            tenantId,
            sessionId,
            actorId);
    if (row == null) throw rejected("CLIPBOARD_BRIDGE_NOT_FOUND");
    return row;
  }

  private BridgeRow query(String sql, Object... args) {
    return jdbc
        .query(
            sql,
            (result, row) ->
                new BridgeRow(
                    result.getString("bridge_id"),
                    result.getString("tenant_id"),
                    result.getString("session_id"),
                    ClipboardBridgeDirection.valueOf(result.getString("direction")),
                    ClipboardBridgePurpose.valueOf(result.getString("purpose")),
                    result.getString("connection_id"),
                    result.getString("request_hash"),
                    result.getLong("agent_clipboard_version"),
                    result.getString("content_hash"),
                    result.getInt("value_length"),
                    result.getString("state"),
                    result.getTimestamp("expires_at").toInstant(),
                    result.getTimestamp("completed_at") == null
                        ? null
                        : result.getTimestamp("completed_at").toInstant(),
                    result.getTimestamp("created_at").toInstant()),
            args)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void expire(String bridgeId, String tenantId, String sessionId, String actorId) {
    jdbc.update(
        "UPDATE agent_clipboard_bridges SET state='EXPIRED', updated_at=? WHERE bridge_id=? AND tenant_id=? AND session_id=? AND actor_id=? AND state='ISSUED'",
        timestamp(Instant.now()),
        bridgeId,
        tenantId,
        sessionId,
        actorId);
  }

  private void appendAudit(
      BridgeRow row, String actorId, String requestId, String action, String outcome) {
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("direction", row.direction().name());
    metadata.put("purpose", row.purpose().name());
    metadata.put("connectionId", row.connectionId());
    metadata.put("agentClipboardVersion", row.agentClipboardVersion());
    metadata.put("contentHash", row.contentHash());
    metadata.put("valueLength", row.valueLength());
    metadata.put("plaintextPersisted", false);
    audit.append(
        new AuditApplicationService.AuditRecord(
            row.tenantId(),
            row.sessionId(),
            "CLIPBOARD_BRIDGE",
            "HUMAN",
            actorId,
            "CLIPBOARD_BRIDGE",
            row.bridgeId(),
            action,
            outcome,
            Map.copyOf(metadata),
            requestId));
  }

  private static ClipboardBridgeView view(BridgeRow row, String value) {
    return new ClipboardBridgeView(
        row.bridgeId(),
        row.sessionId(),
        row.direction(),
        row.purpose(),
        row.connectionId(),
        row.state(),
        row.agentClipboardVersion(),
        row.contentHash(),
        row.valueLength(),
        value,
        row.expiresAt(),
        row.completedAt(),
        row.createdAt());
  }

  private static void requireFreshUserClipboard(Instant observedAt, Instant now) {
    if (observedAt.isAfter(now.plusSeconds(5))
        || observedAt.isBefore(now.minus(USER_CLIPBOARD_MAX_AGE))) {
      throw rejected("USER_CLIPBOARD_STALE");
    }
  }

  private static void requireTenant(String actual, String expected, String sessionId) {
    if (!actual.equals(expected)) throw new TenantAccessDeniedException(sessionId);
  }

  private String requestHash(String sessionId, CreateClipboardBridgeRequest request) {
    var valueFingerprint =
        request.value() == null ? "none" : payloads.fingerprintReference(request.value());
    return PromptSecurityService.sha256(
        sessionId
            + "\n"
            + request.direction()
            + "\n"
            + request.purpose()
            + "\n"
            + request.connectionId()
            + "\n"
            + request.expectedAgentClipboardVersion()
            + "\n"
            + valueFingerprint
            + "\n"
            + request.userClipboardObservedAt());
  }

  private static String id(String prefix, int length) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, length);
  }

  private static java.sql.Timestamp timestamp(Instant value) {
    return java.sql.Timestamp.from(value);
  }

  private static AgentClipboardBridgeRejectedException rejected(String reason) {
    return new AgentClipboardBridgeRejectedException(reason);
  }

  private record BridgeRow(
      String bridgeId,
      String tenantId,
      String sessionId,
      ClipboardBridgeDirection direction,
      ClipboardBridgePurpose purpose,
      String connectionId,
      String requestHash,
      long agentClipboardVersion,
      String contentHash,
      int valueLength,
      String state,
      Instant expiresAt,
      Instant completedAt,
      Instant createdAt) {}

  private static final class MessageHashes {
    private static boolean equal(String left, String right) {
      return java.security.MessageDigest.isEqual(
          left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
  }

  public static final class AgentClipboardBridgeRejectedException extends RuntimeException {
    public AgentClipboardBridgeRejectedException(String reason) {
      super(reason);
    }
  }
}
