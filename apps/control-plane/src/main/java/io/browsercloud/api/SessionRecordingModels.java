package io.browsercloud.api;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** Public immutable recording-manifest metadata; storage coordinates are never exposed. */
public final class SessionRecordingModels {

  private SessionRecordingModels() {}

  public record RecordingView(
      String recordingId,
      String nodeId,
      long segmentCount,
      long frameCount,
      long droppedFrames,
      long redactedFrameCount,
      long redactedRegionCount,
      int redactionPolicyVersion,
      String manifestSha256,
      long manifestBytes,
      Instant startedAt,
      Instant endedAt,
      Instant retentionUntil,
      boolean legalHold) {}

  public record RecordingListResponse(List<RecordingView> items, int limit, int offset) {
    public RecordingListResponse {
      items = List.copyOf(items);
    }
  }

  public enum RecordingPlaybackPurpose {
    INCIDENT_RESPONSE,
    SUPPORT_DIAGNOSTICS,
    COMPLIANCE_AUDIT,
    SECURITY_INVESTIGATION
  }

  public record CreateRecordingPlaybackGrantRequest(@NotNull RecordingPlaybackPurpose purpose) {}

  public record RecordingPlaybackGrantView(
      String grantId,
      String sessionId,
      String recordingId,
      RecordingPlaybackPurpose purpose,
      String state,
      Instant expiresAt,
      Instant createdAt,
      Instant redeemedAt,
      String errorCode,
      String requestId) {}

  /** One immutable, integrity-bound NDJSON recording segment with an ephemeral read URL. */
  public record RecordingPlaybackSegment(
      long sequence,
      String contentSha256,
      long contentBytes,
      long frameCount,
      long startedAtMs,
      long endedAtMs,
      String downloadUrl) {}

  /** Ephemeral response only. Signed segment URLs are never persisted by the Control Plane. */
  public record RedeemRecordingPlaybackGrantResponse(
      String grantId,
      String recordingId,
      String manifestSha256,
      long frameCount,
      long redactedFrameCount,
      long redactedRegionCount,
      int redactionPolicyVersion,
      Instant expiresAt,
      Instant accessExpiresAt,
      Long nextSegmentOffset,
      List<RecordingPlaybackSegment> segments) {
    public RedeemRecordingPlaybackGrantResponse {
      segments = List.copyOf(segments);
    }
  }
}
