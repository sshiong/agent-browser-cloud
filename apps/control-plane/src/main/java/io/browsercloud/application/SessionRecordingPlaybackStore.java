package io.browsercloud.application;

import static io.browsercloud.api.SessionRecordingModels.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, URL-free ledger for one-time recording playback grants. */
@Service
public class SessionRecordingPlaybackStore {

  private final JdbcTemplate jdbc;

  public SessionRecordingPlaybackStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public Optional<RecordingRecord> findAvailableRecording(
      String tenantId, String sessionId, String recordingId, Instant now) {
    return jdbc
        .query(
            """
            SELECT recording.recording_id, recording.node_id, recording.manifest_sha256,
                   recording.manifest_bytes, recording.segment_count, recording.frame_count,
                   recording.redacted_frame_count, recording.redacted_region_count,
                   recording.redaction_policy_version, recording.started_at, recording.ended_at,
                   session.profile_id
              FROM session_recordings recording
              JOIN sessions session
                ON session.id = recording.session_id
               AND session.tenant_id = recording.tenant_id
             WHERE recording.tenant_id = ?
               AND recording.session_id = ?
               AND recording.recording_id = ?
               AND recording.deleted_at IS NULL
               AND (recording.legal_hold OR recording.retention_until > ?)
            """,
            (result, rowNumber) ->
                new RecordingRecord(
                    result.getString("recording_id"),
                    result.getString("node_id"),
                    result.getString("profile_id"),
                    result.getString("manifest_sha256"),
                    result.getLong("manifest_bytes"),
                    result.getLong("segment_count"),
                    result.getLong("frame_count"),
                    result.getLong("redacted_frame_count"),
                    result.getLong("redacted_region_count"),
                    result.getInt("redaction_policy_version"),
                    result.getTimestamp("started_at").toInstant(),
                    result.getTimestamp("ended_at").toInstant()),
            tenantId,
            sessionId,
            recordingId,
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  public boolean insertGrant(
      String grantId,
      String tenantId,
      String sessionId,
      String recordingId,
      String actorId,
      RecordingPlaybackPurpose purpose,
      String idempotencyKey,
      String requestId,
      Instant expiresAt,
      Instant now) {
    return jdbc.update(
            """
            INSERT INTO session_recording_playback_grants(
                grant_id, tenant_id, session_id, recording_id, actor_id, purpose,
                idempotency_key, request_id, state, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?, ?)
            ON CONFLICT (tenant_id, actor_id, idempotency_key) DO NOTHING
            """,
            grantId,
            tenantId,
            sessionId,
            recordingId,
            actorId,
            purpose.name(),
            idempotencyKey,
            requestId,
            Timestamp.from(expiresAt),
            Timestamp.from(now))
        == 1;
  }

  @Transactional(readOnly = true)
  public Optional<RecordingPlaybackGrantView> findGrantByIdempotency(
      String tenantId, String actorId, String idempotencyKey) {
    return grantViews(
            """
            SELECT * FROM session_recording_playback_grants
             WHERE tenant_id = ? AND actor_id = ? AND idempotency_key = ?
            """,
            tenantId,
            actorId,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  @Transactional
  public RecordingPlaybackClaim claim(
      String tenantId, String sessionId, String grantId, String actorId, Instant now) {
    var claims =
        jdbc.query(
            """
            SELECT access_grant.grant_id, access_grant.recording_id,
                   recording.node_id, recording.manifest_sha256, recording.manifest_bytes,
                   recording.segment_count, recording.frame_count,
                   recording.redacted_frame_count, recording.redacted_region_count,
                   recording.redaction_policy_version, recording.started_at, recording.ended_at,
                   recording.retention_until, recording.legal_hold, session.profile_id
              FROM session_recording_playback_grants access_grant
              JOIN session_recordings recording
                ON recording.recording_id = access_grant.recording_id
               AND recording.tenant_id = access_grant.tenant_id
               AND recording.session_id = access_grant.session_id
              JOIN sessions session
                ON session.id = access_grant.session_id
               AND session.tenant_id = access_grant.tenant_id
             WHERE access_grant.tenant_id = ?
               AND access_grant.session_id = ?
               AND access_grant.grant_id = ?
               AND access_grant.actor_id = ?
               AND access_grant.state = 'ISSUED'
               AND access_grant.expires_at > ?
               AND recording.deleted_at IS NULL
               AND (recording.legal_hold OR recording.retention_until > ?)
             FOR UPDATE OF access_grant
            """,
            (result, rowNumber) ->
                new RecordingPlaybackClaim(
                    result.getString("grant_id"),
                    result.getString("recording_id"),
                    result.getString("node_id"),
                    result.getString("profile_id"),
                    result.getString("manifest_sha256"),
                    result.getLong("manifest_bytes"),
                    result.getLong("segment_count"),
                    result.getLong("frame_count"),
                    result.getLong("redacted_frame_count"),
                    result.getLong("redacted_region_count"),
                    result.getInt("redaction_policy_version"),
                    result.getTimestamp("started_at").toInstant(),
                    result.getTimestamp("ended_at").toInstant()),
            tenantId,
            sessionId,
            grantId,
            actorId,
            Timestamp.from(now),
            Timestamp.from(now));
    if (claims.isEmpty()
        || jdbc.update(
                """
                UPDATE session_recording_playback_grants
                   SET state = 'REDEEMING', redeem_started_at = ?
                 WHERE grant_id = ? AND state = 'ISSUED'
                """,
                Timestamp.from(now),
                grantId)
            != 1) {
      throw new SessionRecordingPlaybackApplicationService.RecordingPlaybackRejectedException(
          "RECORDING_PLAYBACK_GRANT_NOT_REDEEMABLE");
    }
    return claims.getFirst();
  }

  @Transactional
  public void commitGrant(String grantId, String nodeId, Instant accessExpiresAt, Instant now) {
    if (jdbc.update(
            """
            UPDATE session_recording_playback_grants
               SET state = 'REDEEMED', redeemed_at = ?, signer_node_id = ?, access_expires_at = ?
             WHERE grant_id = ? AND state = 'REDEEMING'
            """,
            Timestamp.from(now),
            nodeId,
            Timestamp.from(accessExpiresAt),
            grantId)
        != 1) {
      throw new SessionRecordingPlaybackApplicationService.RecordingPlaybackRejectedException(
          "RECORDING_PLAYBACK_GRANT_STATE_CHANGED");
    }
  }

  @Transactional(readOnly = true)
  public RecordingPlaybackPageClaim page(
      String tenantId,
      String sessionId,
      String grantId,
      String actorId,
      long segmentOffset,
      Instant now) {
    return jdbc
        .query(
            """
            SELECT access_grant.grant_id, access_grant.recording_id,
                   access_grant.access_expires_at, recording.node_id,
                   recording.manifest_sha256, recording.manifest_bytes,
                   recording.segment_count, recording.frame_count,
                   recording.redacted_frame_count, recording.redacted_region_count,
                   recording.redaction_policy_version, recording.started_at, recording.ended_at,
                   recording.retention_until, recording.legal_hold, session.profile_id
              FROM session_recording_playback_grants access_grant
              JOIN session_recordings recording
                ON recording.recording_id = access_grant.recording_id
               AND recording.tenant_id = access_grant.tenant_id
               AND recording.session_id = access_grant.session_id
              JOIN sessions session
                ON session.id = access_grant.session_id
               AND session.tenant_id = access_grant.tenant_id
             WHERE access_grant.tenant_id = ?
               AND access_grant.session_id = ?
               AND access_grant.grant_id = ?
               AND access_grant.actor_id = ?
               AND access_grant.state = 'REDEEMED'
               AND access_grant.access_expires_at > ?
               AND ? < recording.segment_count
               AND recording.deleted_at IS NULL
               AND (recording.legal_hold OR recording.retention_until > ?)
            """,
            (result, rowNumber) ->
                new RecordingPlaybackPageClaim(
                    result.getString("grant_id"),
                    result.getString("recording_id"),
                    result.getString("node_id"),
                    result.getString("profile_id"),
                    result.getString("manifest_sha256"),
                    result.getLong("manifest_bytes"),
                    result.getLong("segment_count"),
                    result.getLong("frame_count"),
                    result.getLong("redacted_frame_count"),
                    result.getLong("redacted_region_count"),
                    result.getInt("redaction_policy_version"),
                    result.getTimestamp("started_at").toInstant(),
                    result.getTimestamp("ended_at").toInstant(),
                    result.getTimestamp("access_expires_at").toInstant(),
                    segmentOffset),
            tenantId,
            sessionId,
            grantId,
            actorId,
            Timestamp.from(now),
            segmentOffset,
            Timestamp.from(now))
        .stream()
        .findFirst()
        .orElseThrow(
            () ->
                new SessionRecordingPlaybackApplicationService.RecordingPlaybackRejectedException(
                    "RECORDING_PLAYBACK_PAGE_NOT_AVAILABLE"));
  }

  @Transactional
  public void failGrant(String grantId, String errorCode, Instant now) {
    jdbc.update(
        """
        UPDATE session_recording_playback_grants
           SET state = 'FAILED', redeemed_at = ?, error_code = ?
         WHERE grant_id = ? AND state = 'REDEEMING'
        """,
        Timestamp.from(now),
        errorCode,
        grantId);
  }

  private List<RecordingPlaybackGrantView> grantViews(String sql, Object... arguments) {
    return jdbc.query(
        sql,
        (result, rowNumber) ->
            new RecordingPlaybackGrantView(
                result.getString("grant_id"),
                result.getString("session_id"),
                result.getString("recording_id"),
                RecordingPlaybackPurpose.valueOf(result.getString("purpose")),
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

  public record RecordingRecord(
      String recordingId,
      String nodeId,
      String profileId,
      String manifestSha256,
      long manifestBytes,
      long segmentCount,
      long frameCount,
      long redactedFrameCount,
      long redactedRegionCount,
      int redactionPolicyVersion,
      Instant startedAt,
      Instant endedAt) {}

  public record RecordingPlaybackClaim(
      String grantId,
      String recordingId,
      String nodeId,
      String profileId,
      String manifestSha256,
      long manifestBytes,
      long segmentCount,
      long frameCount,
      long redactedFrameCount,
      long redactedRegionCount,
      int redactionPolicyVersion,
      Instant startedAt,
      Instant endedAt) {}

  public record RecordingPlaybackPageClaim(
      String grantId,
      String recordingId,
      String nodeId,
      String profileId,
      String manifestSha256,
      long manifestBytes,
      long segmentCount,
      long frameCount,
      long redactedFrameCount,
      long redactedRegionCount,
      int redactionPolicyVersion,
      Instant startedAt,
      Instant endedAt,
      Instant accessExpiresAt,
      long segmentOffset) {}
}
