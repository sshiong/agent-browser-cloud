package io.browsercloud.application;

import java.time.Instant;

/** Exact version-scoped cloud hold execution. An unknown result is never an OFF receipt. */
public interface RecordingLegalHoldNodeGateway {
  HoldProof apply(HoldRequest request);

  enum HoldMode {
    ON,
    OFF
  }

  record HoldRequest(
      String operationId,
      long authorityEpoch,
      String nodeId,
      String tenantId,
      String profileId,
      String sessionId,
      String recordingId,
      String manifestSha256,
      long manifestBytes,
      long segmentCount,
      HoldMode mode,
      String previousProofHash,
      long executionBudgetMillis) {}

  record HoldProof(
      String operationId,
      long authorityEpoch,
      String nodeId,
      String recordingId,
      HoldMode mode,
      String proofHash,
      String inventorySha256,
      long objectVersionCount,
      Instant completedAt) {}

  /** No RPC was initiated by this attempt; it says nothing about any previous attempt. */
  final class HoldNotDispatchedException extends RuntimeException {
    public HoldNotDispatchedException(String code) {
      super(code);
    }
  }

  /** Dispatch may have changed remote state; reconciliation must preserve the exact request. */
  final class HoldResultUnknownException extends RuntimeException {
    public HoldResultUnknownException(String code) {
      super(code);
    }
  }
}
