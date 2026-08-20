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
    String documentReadyState,
    long networkQuietMillis,
    boolean networkEvidenceFresh,
    List<InteractiveTargetView> targets) {

  public BrowserStateView {
    targets = List.copyOf(targets);
  }

  public record InteractiveTargetView(
      String targetRef,
      String elementId,
      String role,
      String name,
      String value,
      String controlType,
      BoundsView bounds,
      boolean enabled,
      boolean visible,
      boolean sensitive,
      boolean focused,
      Boolean checked,
      Boolean selected,
      boolean interactive,
      String frameId,
      boolean inViewport,
      boolean occluded,
      String visibilityReason) {}

  public record BoundsView(double x, double y, double width, double height) {}
}
