package io.browsercloud.api;

import java.time.Instant;
import java.util.List;

public final class SafePointModels {

  private SafePointModels() {}

  public record NodeSafetyObservation(
      Boolean inputActive,
      Boolean activeDrag,
      Integer pressedKeyCount,
      Integer pressedButtonCount,
      Integer activeUploadCount,
      Integer activeDownloadCount,
      Integer activeFormSubmissionCount,
      Instant observedAt) {

    public boolean hasInputObservation() {
      return inputActive != null
          || activeDrag != null
          || pressedKeyCount != null
          || pressedButtonCount != null;
    }

    public boolean hasBrowserActivityObservation() {
      return activeUploadCount != null
          || activeDownloadCount != null
          || activeFormSubmissionCount != null;
    }
  }

  public record SafePointBlockerView(
      String code, String source, String detail, Instant observedAt, Instant expiresAt) {}

  public record SessionSafePointView(
      String sessionId,
      boolean safe,
      String state,
      String dataFreshness,
      String nodeId,
      long contextEpoch,
      Instant evaluatedAt,
      Instant lastNodeObservationAt,
      List<SafePointBlockerView> blockers) {}
}
