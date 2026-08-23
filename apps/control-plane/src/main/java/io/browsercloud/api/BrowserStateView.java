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
    List<InteractiveTargetView> targets,
    List<BrowserTabView> tabs,
    String activeTabId,
    List<NativeDialogView> nativeDialogs,
    boolean nativeDialogEvidenceFresh) {

  public BrowserStateView {
    targets = List.copyOf(targets);
    tabs = tabs == null ? List.of() : List.copyOf(tabs);
    activeTabId = activeTabId == null ? "" : activeTabId;
    nativeDialogs = nativeDialogs == null ? List.of() : List.copyOf(nativeDialogs);
  }

  public BrowserStateView(
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
      List<InteractiveTargetView> targets,
      List<BrowserTabView> tabs,
      String activeTabId) {
    this(
        sessionId,
        contextEpoch,
        stateVersion,
        targetRevision,
        url,
        title,
        stateHash,
        stateQuality,
        documentReadyState,
        networkQuietMillis,
        networkEvidenceFresh,
        targets,
        tabs,
        activeTabId,
        List.of(),
        false);
  }

  public BrowserStateView(
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
    this(
        sessionId,
        contextEpoch,
        stateVersion,
        targetRevision,
        url,
        title,
        stateHash,
        stateQuality,
        documentReadyState,
        networkQuietMillis,
        networkEvidenceFresh,
        targets,
        List.of(),
        "");
  }

  public record BrowserTabView(String tabId, String url, String title, boolean active) {}

  public record NativeDialogView(
      String dialogId,
      String tabId,
      String dialogType,
      String message,
      String defaultPrompt,
      boolean hasBrowserHandler) {}

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
