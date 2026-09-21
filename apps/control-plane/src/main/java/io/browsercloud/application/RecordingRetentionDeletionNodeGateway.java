package io.browsercloud.application;

import java.time.Instant;

/** mTLS-only execution of one exact, retention-authorized Recording object deletion. */
public interface RecordingRetentionDeletionNodeGateway {

  RecordingDeletionProof delete(RecordingDeletionRequest request);

  record RecordingDeletionRequest(
      String jobId,
      long deletionEpoch,
      String nodeId,
      String tenantId,
      String profileId,
      String sessionId,
      String recordingId,
      String manifestSha256,
      long manifestBytes,
      long segmentCount) {}

  record RecordingDeletionProof(
      String jobId,
      long deletionEpoch,
      String nodeId,
      String recordingId,
      String deletionProofHash,
      long deletedObjectCount,
      Instant completedAt) {}

  final class RecordingDeletionNodeUnavailableException extends RuntimeException {
    public RecordingDeletionNodeUnavailableException(String message) {
      super(message);
    }

    public RecordingDeletionNodeUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  final class RecordingDeletionNodeRejectedException extends RuntimeException {
    public RecordingDeletionNodeRejectedException(String message) {
      super(message);
    }

    public RecordingDeletionNodeRejectedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
