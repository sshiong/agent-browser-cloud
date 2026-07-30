package io.browsercloud.application;

import static io.browsercloud.api.SessionEvidenceModels.*;

import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists the Node-authoritative screenshot evidence index and exposes tenant-scoped metadata. */
@Service
public class SessionEvidenceApplicationService {

  private final JdbcTemplate jdbc;
  private final SessionRepository sessions;
  private final SessionEvidenceGovernanceStore governance;

  public SessionEvidenceApplicationService(
      JdbcTemplate jdbc, SessionRepository sessions, SessionEvidenceGovernanceStore governance) {
    this.jdbc = jdbc;
    this.sessions = sessions;
    this.governance = governance;
  }

  @Transactional
  public void record(String tenantId, String eventId, NodeEvent.EvidenceCaptured evidence) {
    jdbc.update(
        """
        INSERT INTO session_evidence(
            evidence_id, event_id, tenant_id, session_id, evidence_kind,
            task_id, step_id, command_id, mandatory, result, content_sha256,
            content_bytes, object_key, error_code, captured_at, redaction_state,
            redacted_region_count)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (event_id) DO NOTHING
        """,
        evidence.evidenceId(),
        eventId,
        tenantId,
        evidence.sessionId(),
        evidence.evidenceKind(),
        evidence.taskId(),
        evidence.stepId(),
        evidence.commandId(),
        evidence.mandatory(),
        evidence.result(),
        nullable(evidence.contentSha256()),
        evidence.contentBytes(),
        nullable(evidence.objectKey()),
        nullable(evidence.errorCode()),
        Timestamp.from(Instant.ofEpochMilli(evidence.capturedAtMs())),
        evidence.redactionState(),
        evidence.redactedRegionCount());
    if ("OBSERVER_MANUAL".equals(evidence.evidenceKind())) {
      governance.completeCaptureFromEvidence(
          tenantId,
          evidence.sessionId(),
          evidence.commandId(),
          evidence.evidenceId(),
          evidence.result(),
          evidence.errorCode(),
          Instant.ofEpochMilli(evidence.capturedAtMs()));
    }
  }

  @Transactional(readOnly = true)
  public EvidenceListResponse list(String sessionId, String tenantId, int limit, int offset) {
    requireTenant(sessionId, tenantId);
    var safeLimit = Math.max(1, Math.min(limit, 100));
    var safeOffset = Math.max(0, offset);
    var items =
        jdbc.query(
            """
            SELECT evidence_id, evidence_kind, task_id, step_id, command_id,
                   mandatory, result, content_sha256, content_bytes, captured_at, error_code,
                   redaction_state, redacted_region_count
            FROM session_evidence
            WHERE tenant_id = ? AND session_id = ?
            ORDER BY captured_at DESC, evidence_id DESC
            LIMIT ? OFFSET ?
            """,
            (result, rowNumber) ->
                new EvidenceView(
                    result.getString("evidence_id"),
                    result.getString("evidence_kind"),
                    result.getString("task_id"),
                    result.getString("step_id"),
                    result.getString("command_id"),
                    result.getBoolean("mandatory"),
                    result.getString("result"),
                    result.getString("content_sha256"),
                    result.getLong("content_bytes"),
                    result.getTimestamp("captured_at").toInstant(),
                    result.getString("error_code"),
                    result.getString("redaction_state"),
                    result.getInt("redacted_region_count")),
            tenantId,
            sessionId,
            safeLimit,
            safeOffset);
    return new EvidenceListResponse(items, safeLimit, safeOffset);
  }

  private void requireTenant(String sessionId, String tenantId) {
    if (!tenantId.equals(sessions.require(sessionId).tenantId())) {
      throw new SessionNotFoundException(sessionId);
    }
  }

  private static String nullable(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
