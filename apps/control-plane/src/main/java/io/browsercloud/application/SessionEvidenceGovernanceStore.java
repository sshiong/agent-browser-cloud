package io.browsercloud.application;

import static io.browsercloud.api.SessionEvidenceModels.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transaction boundaries for durable Observer capture requests and one-time access grants. */
@Service
public class SessionEvidenceGovernanceStore {

  private final JdbcTemplate jdbc;

  public SessionEvidenceGovernanceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean insertCapture(
      String captureId,
      String tenantId,
      String sessionId,
      String actorId,
      EvidencePurpose purpose,
      String idempotencyKey,
      String commandId,
      String requestId,
      Instant now) {
    return jdbc.update(
            """
            INSERT INTO session_evidence_capture_requests(
                capture_id, tenant_id, session_id, actor_id, purpose, idempotency_key,
                command_id, request_id, state, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'EXECUTING', ?)
            ON CONFLICT (tenant_id, actor_id, idempotency_key) DO NOTHING
            """,
            captureId,
            tenantId,
            sessionId,
            actorId,
            purpose.name(),
            idempotencyKey,
            commandId,
            requestId,
            Timestamp.from(now))
        == 1;
  }

  @Transactional(readOnly = true)
  public Optional<EvidenceCaptureView> findCaptureByIdempotency(
      String tenantId, String actorId, String idempotencyKey) {
    return captureViews(
            """
            SELECT *
            FROM session_evidence_capture_requests
            WHERE tenant_id = ? AND actor_id = ? AND idempotency_key = ?
            """,
            tenantId,
            actorId,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public Optional<EvidenceCaptureView> findCapture(
      String tenantId, String sessionId, String captureId) {
    return captureViews(
            """
            SELECT *
            FROM session_evidence_capture_requests
            WHERE tenant_id = ? AND session_id = ? AND capture_id = ?
            """,
            tenantId,
            sessionId,
            captureId)
        .stream()
        .findFirst();
  }

  public void completeCaptureFromEvidence(
      String tenantId,
      String sessionId,
      String commandId,
      String evidenceId,
      String result,
      String errorCode,
      Instant completedAt) {
    if (!"COMMITTED".equals(result) && !"FAILED".equals(result)) {
      return;
    }
    jdbc.update(
        """
        UPDATE session_evidence_capture_requests
        SET state = ?,
            evidence_id = ?,
            error_code = ?,
            completed_at = ?
        WHERE tenant_id = ?
          AND session_id = ?
          AND command_id = ?
          AND state = 'EXECUTING'
        """,
        result,
        evidenceId,
        "FAILED".equals(result) ? nonBlank(errorCode, "CAPTURE_FAILED") : null,
        Timestamp.from(completedAt),
        tenantId,
        sessionId,
        commandId);
  }

  @Transactional
  public void failCaptureDispatch(String commandId, String errorCode, Instant completedAt) {
    jdbc.update(
        """
        UPDATE session_evidence_capture_requests
        SET state = 'FAILED', error_code = ?, completed_at = ?
        WHERE command_id = ? AND state = 'EXECUTING'
        """,
        nonBlank(errorCode, "NODE_COMMAND_DEAD_LETTERED"),
        Timestamp.from(completedAt),
        commandId);
  }

  public boolean insertGrant(
      String grantId,
      String tenantId,
      String sessionId,
      String evidenceId,
      String actorId,
      EvidencePurpose purpose,
      String idempotencyKey,
      String requestId,
      Instant expiresAt,
      Instant now) {
    return jdbc.update(
            """
            INSERT INTO session_evidence_access_grants(
                grant_id, tenant_id, session_id, evidence_id, actor_id, purpose,
                idempotency_key, request_id, state, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?, ?)
            ON CONFLICT (tenant_id, actor_id, idempotency_key) DO NOTHING
            """,
            grantId,
            tenantId,
            sessionId,
            evidenceId,
            actorId,
            purpose.name(),
            idempotencyKey,
            requestId,
            Timestamp.from(expiresAt),
            Timestamp.from(now))
        == 1;
  }

  @Transactional(readOnly = true)
  public Optional<EvidenceAccessGrantView> findGrantByIdempotency(
      String tenantId, String actorId, String idempotencyKey) {
    return grantViews(
            """
            SELECT *
            FROM session_evidence_access_grants
            WHERE tenant_id = ? AND actor_id = ? AND idempotency_key = ?
            """,
            tenantId,
            actorId,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  @Transactional
  public EvidenceAccessClaim claim(
      String tenantId, String sessionId, String grantId, String actorId, Instant now) {
    var claims =
        jdbc.query(
            """
            SELECT access_grant.grant_id, access_grant.evidence_id,
                   access_grant.expires_at, access_grant.actor_id,
                   evidence.content_sha256, evidence.content_bytes,
                   session.profile_id, placement.node_id
            FROM session_evidence_access_grants access_grant
            JOIN session_evidence evidence
              ON evidence.evidence_id = access_grant.evidence_id
             AND evidence.tenant_id = access_grant.tenant_id
             AND evidence.session_id = access_grant.session_id
            JOIN sessions session
              ON session.id = access_grant.session_id
             AND session.tenant_id = access_grant.tenant_id
            JOIN browser_placements placement
              ON placement.session_id = access_grant.session_id
             AND placement.tenant_id = access_grant.tenant_id
            WHERE access_grant.tenant_id = ?
              AND access_grant.session_id = ?
              AND access_grant.grant_id = ?
              AND access_grant.actor_id = ?
              AND access_grant.state = 'ISSUED'
              AND access_grant.expires_at > ?
              AND evidence.result = 'COMMITTED'
            FOR UPDATE OF access_grant
            """,
            (result, rowNumber) ->
                new EvidenceAccessClaim(
                    result.getString("grant_id"),
                    result.getString("evidence_id"),
                    result.getString("profile_id"),
                    result.getString("node_id"),
                    result.getString("content_sha256"),
                    result.getLong("content_bytes"),
                    result.getTimestamp("expires_at").toInstant()),
            tenantId,
            sessionId,
            grantId,
            actorId,
            Timestamp.from(now));
    if (claims.isEmpty()) {
      throw new SessionEvidenceGovernanceService.EvidenceGovernanceRejectedException(
          "EVIDENCE_ACCESS_GRANT_NOT_REDEEMABLE");
    }
    if (jdbc.update(
            """
            UPDATE session_evidence_access_grants
            SET state = 'REDEEMING', redeem_started_at = ?
            WHERE grant_id = ? AND state = 'ISSUED'
            """,
            Timestamp.from(now),
            grantId)
        != 1) {
      throw new SessionEvidenceGovernanceService.EvidenceGovernanceRejectedException(
          "EVIDENCE_ACCESS_GRANT_NOT_REDEEMABLE");
    }
    return claims.getFirst();
  }

  @Transactional
  public void commitGrant(String grantId, String nodeId, Instant now) {
    if (jdbc.update(
            """
            UPDATE session_evidence_access_grants
            SET state = 'REDEEMED', redeemed_at = ?, signer_node_id = ?
            WHERE grant_id = ? AND state = 'REDEEMING'
            """,
            Timestamp.from(now),
            nodeId,
            grantId)
        != 1) {
      throw new SessionEvidenceGovernanceService.EvidenceGovernanceRejectedException(
          "EVIDENCE_ACCESS_GRANT_STATE_CHANGED");
    }
  }

  @Transactional
  public void failGrant(String grantId, String errorCode, Instant now) {
    jdbc.update(
        """
        UPDATE session_evidence_access_grants
        SET state = 'FAILED', redeemed_at = ?, error_code = ?
        WHERE grant_id = ? AND state = 'REDEEMING'
        """,
        Timestamp.from(now),
        nonBlank(errorCode, "EVIDENCE_ACCESS_NODE_FAILED"),
        grantId);
  }

  @Transactional(readOnly = true)
  public Optional<EvidenceRecord> findCommittedEvidence(
      String tenantId, String sessionId, String evidenceId) {
    return jdbc
        .query(
            """
            SELECT evidence_id, content_sha256, content_bytes
            FROM session_evidence
            WHERE tenant_id = ?
              AND session_id = ?
              AND evidence_id = ?
              AND result = 'COMMITTED'
            """,
            (result, rowNumber) ->
                new EvidenceRecord(
                    result.getString("evidence_id"),
                    result.getString("content_sha256"),
                    result.getLong("content_bytes")),
            tenantId,
            sessionId,
            evidenceId)
        .stream()
        .findFirst();
  }

  private List<EvidenceCaptureView> captureViews(String sql, Object... arguments) {
    return jdbc.query(
        sql,
        (result, rowNumber) ->
            new EvidenceCaptureView(
                result.getString("capture_id"),
                result.getString("session_id"),
                EvidencePurpose.valueOf(result.getString("purpose")),
                result.getString("state"),
                result.getString("evidence_id"),
                result.getString("error_code"),
                result.getString("command_id"),
                result.getString("request_id"),
                result.getTimestamp("created_at").toInstant(),
                instant(result.getTimestamp("completed_at"))),
        arguments);
  }

  private List<EvidenceAccessGrantView> grantViews(String sql, Object... arguments) {
    return jdbc.query(
        sql,
        (result, rowNumber) ->
            new EvidenceAccessGrantView(
                result.getString("grant_id"),
                result.getString("session_id"),
                result.getString("evidence_id"),
                EvidencePurpose.valueOf(result.getString("purpose")),
                result.getString("state"),
                result.getTimestamp("expires_at").toInstant(),
                result.getTimestamp("created_at").toInstant(),
                instant(result.getTimestamp("redeemed_at")),
                result.getString("error_code"),
                result.getString("request_id")),
        arguments);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static String nonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  public record EvidenceRecord(String evidenceId, String contentSha256, long contentBytes) {}

  public record EvidenceAccessClaim(
      String grantId,
      String evidenceId,
      String profileId,
      String nodeId,
      String contentSha256,
      long contentBytes,
      Instant grantExpiresAt) {}
}
