package io.browsercloud.application;

import java.time.Instant;
import java.util.List;

/** mTLS-only request to verify and sign one exact immutable recording and all of its segments. */
public interface SessionRecordingPlaybackNodeGateway {

  SignedRecordingPlayback sign(SignRecordingPlaybackRequest request);

  record SignRecordingPlaybackRequest(
      String grantId,
      String nodeId,
      String tenantId,
      String profileId,
      String sessionId,
      String recordingId,
      String manifestSha256,
      long manifestBytes,
      long segmentCount,
      long frameCount,
      long redactedFrameCount,
      long redactedRegionCount,
      int redactionPolicyVersion,
      long startedAtMs,
      long endedAtMs,
      long segmentOffset,
      int segmentLimit,
      int expiresInSeconds) {}

  record SignedRecordingSegment(
      long sequence,
      String contentSha256,
      long contentBytes,
      long frameCount,
      long startedAtMs,
      long endedAtMs,
      String downloadUrl) {}

  record SignedRecordingPlayback(
      String grantId,
      String nodeId,
      String recordingId,
      String manifestSha256,
      long frameCount,
      long redactedFrameCount,
      long redactedRegionCount,
      int redactionPolicyVersion,
      Instant expiresAt,
      Long nextSegmentOffset,
      List<SignedRecordingSegment> segments) {
    public SignedRecordingPlayback {
      segments = List.copyOf(segments);
    }
  }

  final class RecordingPlaybackNodeUnavailableException extends RuntimeException {
    public RecordingPlaybackNodeUnavailableException(String message) {
      super(message);
    }

    public RecordingPlaybackNodeUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  final class RecordingPlaybackNodeRejectedException extends RuntimeException {
    public RecordingPlaybackNodeRejectedException(String message) {
      super(message);
    }

    public RecordingPlaybackNodeRejectedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
