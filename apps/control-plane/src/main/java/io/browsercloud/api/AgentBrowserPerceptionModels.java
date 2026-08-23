package io.browsercloud.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Coarse structured-perception API models shared by Agent callers and operator diagnostics. */
public final class AgentBrowserPerceptionModels {

  private AgentBrowserPerceptionModels() {}

  public record SnapshotView(
      String stateCursor,
      BrowserStateView state,
      String visibleTextSummary,
      List<TabView> tabs,
      TabView activeTab,
      String focusedElementId,
      List<String> formControlElementIds,
      List<String> dialogElementIds,
      List<NativeDialogView> nativeDialogs,
      boolean nativeDialogEvidenceFresh,
      String pageLoadingState,
      String challengeState,
      boolean visionRecommended) {
    public SnapshotView(String stateCursor, BrowserStateView state) {
      this(
          stateCursor,
          state,
          "",
          state.tabs().stream()
              .map(tab -> new TabView(tab.tabId(), tab.url(), tab.title(), tab.active()))
              .toList(),
          state.tabs().stream()
              .filter(tab -> tab.active() && tab.tabId().equals(state.activeTabId()))
              .findFirst()
              .map(tab -> new TabView(tab.tabId(), tab.url(), tab.title(), true))
              .orElse(null),
          null,
          List.of(),
          List.of(),
          state.nativeDialogs().stream()
              .map(
                  dialog ->
                      new NativeDialogView(
                          dialog.dialogId(),
                          dialog.tabId(),
                          dialog.dialogType(),
                          dialog.message(),
                          dialog.defaultPrompt(),
                          dialog.hasBrowserHandler()))
              .toList(),
          state.nativeDialogEvidenceFresh(),
          state.documentReadyState(),
          "NOT_EVALUATED",
          false);
    }
  }

  public record TabView(String tabId, String url, String title, boolean active) {}

  public record NativeDialogView(
      String dialogId,
      String tabId,
      String dialogType,
      String message,
      String defaultPrompt,
      boolean hasBrowserHandler) {}

  public record InspectRequest(
      @NotBlank @Pattern(regexp = "^[0-9]+:[0-9]+:[a-f0-9]{64}$") String stateCursor,
      @NotNull @Size(min = 1, max = 50) List<@NotBlank @Size(max = 256) String> elementIds) {
    public InspectRequest {
      elementIds = elementIds == null ? null : List.copyOf(elementIds);
    }
  }

  public record FindRequest(
      @NotBlank @Size(max = 256) String query,
      @Size(max = 16) List<@Size(max = 64) String> roles,
      boolean includeHidden,
      @Min(1) @Max(100) Integer limit) {
    public FindRequest {
      roles = roles == null ? List.of() : List.copyOf(roles);
    }
  }

  public record TargetListView(
      String stateCursor, List<BrowserStateView.InteractiveTargetView> targets, boolean truncated) {
    public TargetListView {
      targets = List.copyOf(targets);
    }
  }
}
