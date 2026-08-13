package io.browsercloud.application;

import static io.browsercloud.api.SessionRecordingModels.*;

import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects Node-authoritative immutable recording manifests into tenant-scoped PostgreSQL. */
@Service
public class SessionRecordingApplicationService {

  private static final int DEFAULT_RETENTION_DAYS = 30;
  private final JdbcTemplate jdbc;
  private final SessionRepository sessions;

  public SessionRecordingApplicationService(JdbcTemplate jdbc, SessionRepository sessions) {
    this.jdbc = jdbc;
    this.sessions = sessions;
  }

  @Transactional
  public void record(String tenantId, String eventId, NodeEvent.RecordingFinalized recording) {
    jdbc.update(
        """
        INSERT INTO session_recordings(
            recording_id, event_id, tenant_id, session_id, node_id,
            segment_count, frame_count, dropped_frames, redacted_frame_count,
            redacted_region_count, redaction_policy_version, manifest_object_key,
            manifest_sha256, manifest_bytes, started_at, ended_at,
            retention_until, legal_hold)
        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
               ? + make_interval(days => COALESCE(policy.retention_days, ?)),
               COALESCE(policy.legal_hold, FALSE)
          FROM (SELECT 1) seed
          LEFT JOIN enterprise_retention_policies policy
            ON policy.tenant_id = ?
           AND policy.data_class = 'REMOTE_DESKTOP_RECORDING'
        ON CONFLICT DO NOTHING
        """,
        recording.recordingId(),
        eventId,
        tenantId,
        recording.sessionId(),
        recording.nodeId(),
        recording.segmentCount(),
        recording.frameCount(),
        recording.droppedFrames(),
        recording.redactedFrameCount(),
        recording.redactedRegionCount(),
        recording.redactionPolicyVersion(),
        recording.manifestObjectKey(),
        recording.manifestSha256(),
        recording.manifestBytes(),
        Timestamp.from(Instant.ofEpochMilli(recording.startedAtMs())),
        Timestamp.from(Instant.ofEpochMilli(recording.endedAtMs())),
        Timestamp.from(Instant.ofEpochMilli(recording.endedAtMs())),
        DEFAULT_RETENTION_DAYS,
        tenantId);
  }

  @Transactional(readOnly = true)
  public RecordingListResponse list(String sessionId, String tenantId, int limit, int offset) {
    requireTenant(sessionId, tenantId);
    var safeLimit = Math.max(1, Math.min(limit, 100));
    var safeOffset = Math.max(0, offset);
    var items =
        jdbc.query(
            """
            SELECT recording_id, node_id, segment_count, frame_count, dropped_frames,
                   redacted_frame_count, redacted_region_count, redaction_policy_version,
                   manifest_sha256, manifest_bytes, started_at, ended_at,
                   retention_until, legal_hold
              FROM session_recordings
             WHERE tenant_id = ? AND session_id = ?
             ORDER BY ended_at DESC, recording_id DESC
             LIMIT ? OFFSET ?
            """,
            (result, row) ->
                new RecordingView(
                    result.getString("recording_id"),
                    result.getString("node_id"),
                    result.getLong("segment_count"),
                    result.getLong("frame_count"),
                    result.getLong("dropped_frames"),
                    result.getLong("redacted_frame_count"),
                    result.getLong("redacted_region_count"),
                    result.getInt("redaction_policy_version"),
                    result.getString("manifest_sha256"),
                    result.getLong("manifest_bytes"),
                    result.getTimestamp("started_at").toInstant(),
                    result.getTimestamp("ended_at").toInstant(),
                    result.getTimestamp("retention_until").toInstant(),
                    result.getBoolean("legal_hold")),
            tenantId,
            sessionId,
            safeLimit,
            safeOffset);
    return new RecordingListResponse(items, safeLimit, safeOffset);
  }

  private void requireTenant(String sessionId, String tenantId) {
    if (!tenantId.equals(sessions.require(sessionId).tenantId())) {
      throw new SessionNotFoundException(sessionId);
    }
  }
}
