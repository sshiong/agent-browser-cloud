package io.browsercloud.api;

import java.util.List;

public record BrowserStateView(
    String sessionId,
    long contextEpoch,
    long stateVersion,
    long targetRevision,
    String url,
    String title,
    String stateHash,
    String stateQuality,
    List<InteractiveTargetView> targets) {

  public BrowserStateView {
    targets = List.copyOf(targets);
  }

  public record InteractiveTargetView(
      String targetRef,
      String role,
      String name,
      BoundsView bounds,
      boolean enabled,
      boolean visible) {}

  public record BoundsView(double x, double y, double width, double height) {}
}
