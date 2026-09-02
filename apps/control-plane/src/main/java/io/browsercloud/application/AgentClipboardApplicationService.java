package io.browsercloud.application;

import static io.browsercloud.api.AgentClipboardModels.*;

import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Encrypted AgentClipboard authority. It has no code path to the VNC/X11 UserClipboard. */
@Service
public class AgentClipboardApplicationService {

  private static final String REFERENCE_PREFIX = "agent-clipboard-v";

  private final JdbcTemplate jdbc;
  private final SessionRepository sessions;
  private final AgentActionPayloadService payloads;
  private final AuditApplicationService audit;

  public AgentClipboardApplicationService(
      JdbcTemplate jdbc,
      SessionRepository sessions,
      AgentActionPayloadService payloads,
      AuditApplicationService audit) {
    this.jdbc = jdbc;
    this.sessions = sessions;
    this.payloads = payloads;
    this.audit = audit;
  }

  @Transactional
  public AgentClipboardView write(
      String sessionId, String tenantId, String actorId, WriteAgentClipboardRequest request) {
    requireTenant(sessionId, tenantId);
    lockSession(sessionId, tenantId);
    var current = currentRow(sessionId, tenantId, true);
    var currentVersion = current == null ? 0 : current.version();
    if (currentVersion != request.expectedVersion()) {
      throw new AgentClipboardRejectedException("AGENT_CLIPBOARD_VERSION_MISMATCH");
    }
    var version = currentVersion + 1;
    var sealed =
        payloads.sealReference(tenantId, sessionId, REFERENCE_PREFIX + version, request.value());
    var hash = PromptSecurityService.sha256(request.value());
    var now = Instant.now();
    var updated =
        jdbc.update(
            """
        INSERT INTO agent_clipboards(
          session_id, tenant_id, sealed_value, content_hash, value_length,
          version, updated_by, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (session_id) DO UPDATE SET
          sealed_value=EXCLUDED.sealed_value,
          content_hash=EXCLUDED.content_hash,
          value_length=EXCLUDED.value_length,
          version=EXCLUDED.version,
          updated_by=EXCLUDED.updated_by,
          updated_at=EXCLUDED.updated_at
        WHERE agent_clipboards.tenant_id=EXCLUDED.tenant_id
          AND agent_clipboards.version=?
        """,
            sessionId,
            tenantId,
            sealed,
            hash,
            request.value().length(),
            version,
            actorId,
            timestamp(now),
            timestamp(now),
            currentVersion);
    if (updated != 1) {
      throw new AgentClipboardRejectedException("AGENT_CLIPBOARD_VERSION_MISMATCH");
    }
    appendAudit(tenantId, sessionId, actorId, "WRITE", version, hash);
    return new AgentClipboardView(sessionId, version, hash, request.value().length(), null, now);
  }

  @Transactional
  public AgentClipboardView read(String sessionId, String tenantId, String actorId) {
    return read(sessionId, tenantId, actorId, true);
  }

  @Transactional
  public AgentClipboardView read(
      String sessionId, String tenantId, String actorId, boolean includeValue) {
    requireTenant(sessionId, tenantId);
    var row = currentRow(sessionId, tenantId, false);
    if (row == null || row.sealedValue() == null) {
      return new AgentClipboardView(
          sessionId,
          row == null ? 0 : row.version(),
          null,
          0,
          null,
          row == null ? null : row.updatedAt());
    }
    var value =
        includeValue
            ? payloads.unsealReference(
                tenantId, sessionId, REFERENCE_PREFIX + row.version(), row.sealedValue())
            : null;
    if (includeValue) {
      appendAudit(tenantId, sessionId, actorId, "READ", row.version(), row.contentHash());
    }
    return new AgentClipboardView(
        sessionId, row.version(), row.contentHash(), row.valueLength(), value, row.updatedAt());
  }

  /** Internal paste materialization; content remains sealed again inside the Action Plan. */
  @Transactional(readOnly = true)
  public String materializeForPaste(String sessionId, String tenantId) {
    requireTenant(sessionId, tenantId);
    var row = currentRow(sessionId, tenantId, false);
    if (row == null || row.sealedValue() == null) {
      throw new AgentClipboardRejectedException("AGENT_CLIPBOARD_EMPTY");
    }
    return payloads.unsealReference(
        tenantId, sessionId, REFERENCE_PREFIX + row.version(), row.sealedValue());
  }

  @Transactional
  public AgentClipboardView clear(
      String sessionId, String tenantId, String actorId, long expectedVersion) {
    requireTenant(sessionId, tenantId);
    lockSession(sessionId, tenantId);
    var row = currentRow(sessionId, tenantId, true);
    var currentVersion = row == null ? 0 : row.version();
    if (currentVersion != expectedVersion) {
      throw new AgentClipboardRejectedException("AGENT_CLIPBOARD_VERSION_MISMATCH");
    }
    var now = Instant.now();
    var version = currentVersion + 1;
    if (row == null) {
      jdbc.update(
          "INSERT INTO agent_clipboards(session_id, tenant_id, value_length, version, updated_by, created_at, updated_at) VALUES (?, ?, 0, ?, ?, ?, ?)",
          sessionId,
          tenantId,
          version,
          actorId,
          timestamp(now),
          timestamp(now));
    } else {
      jdbc.update(
          "UPDATE agent_clipboards SET sealed_value=NULL, content_hash=NULL, value_length=0, version=?, updated_by=?, updated_at=? WHERE session_id=? AND tenant_id=? AND version=?",
          version,
          actorId,
          timestamp(now),
          sessionId,
          tenantId,
          currentVersion);
    }
    appendAudit(tenantId, sessionId, actorId, "CLEAR", version, "empty");
    return new AgentClipboardView(sessionId, version, null, 0, null, now);
  }

  private ClipboardRow currentRow(String sessionId, String tenantId, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM agent_clipboards WHERE session_id=? AND tenant_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (result, row) ->
                new ClipboardRow(
                    result.getString("sealed_value"),
                    result.getString("content_hash"),
                    result.getInt("value_length"),
                    result.getLong("version"),
                    result.getTimestamp("updated_at").toInstant()),
            sessionId,
            tenantId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void requireTenant(String sessionId, String tenantId) {
    if (!sessions.require(sessionId).tenantId().equals(tenantId)) {
      throw new TenantAccessDeniedException(sessionId);
    }
  }

  private void lockSession(String sessionId, String tenantId) {
    jdbc.queryForObject(
        "SELECT id FROM sessions WHERE id=? AND tenant_id=? AND deleted_at IS NULL FOR UPDATE",
        String.class,
        sessionId,
        tenantId);
  }

  private void appendAudit(
      String tenantId,
      String sessionId,
      String actorId,
      String action,
      long version,
      String contentHash) {
    audit.append(
        new AuditApplicationService.AuditRecord(
            tenantId,
            sessionId,
            "AGENT_CLIPBOARD",
            "USER",
            actorId,
            "AGENT_CLIPBOARD",
            sessionId,
            action,
            "COMMITTED",
            Map.of("version", version, "contentHash", contentHash, "userClipboardAccess", false),
            "agent-clipboard:" + sessionId + ":" + version + ":" + action));
  }

  private static java.sql.Timestamp timestamp(Instant value) {
    return java.sql.Timestamp.from(value);
  }

  private record ClipboardRow(
      String sealedValue, String contentHash, int valueLength, long version, Instant updatedAt) {}

  public static final class AgentClipboardRejectedException extends RuntimeException {
    public AgentClipboardRejectedException(String reason) {
      super(reason);
    }
  }
}
