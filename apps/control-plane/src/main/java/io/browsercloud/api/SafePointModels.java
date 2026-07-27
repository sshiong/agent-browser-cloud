package io.browsercloud.api;

import java.time.Instant;
import java.util.List;

public final class SafePointModels {

  private SafePointModels() {}

  public record NodeSafetyObservation(
      boolean inputActive,
      boolean activeDrag,
      int pressedKeyCount,
      int pressedButtonCount,
      Instant observedAt) {}

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
