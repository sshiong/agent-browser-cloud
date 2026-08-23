package io.browsercloud.application;

import static io.browsercloud.api.AgentBrowserPerceptionModels.*;

import io.browsercloud.api.BrowserStateView;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * PostgreSQL-authoritative structured page perception.
 *
 * <p>No browser round trip is made for inspect/find: callers reuse the cursor returned by snapshot,
 * and stale cursors fail closed before an action can be planned.
 */
@Service
public class AgentBrowserPerceptionService {

  private final SessionApplicationService sessions;

  public AgentBrowserPerceptionService(SessionApplicationService sessions) {
    this.sessions = sessions;
  }

  public SnapshotView snapshot(String sessionId, String tenantId) {
    var state =
        sessions
            .getState(sessionId, tenantId)
            .orElseThrow(() -> new PerceptionException("BROWSER_STATE_UNAVAILABLE"));
    requireExecutable(state);
    var visibleText =
        state.targets().stream()
            .filter(target -> target.visible() && !target.sensitive())
            .flatMap(
                target -> java.util.stream.Stream.of(safe(target.name()), safe(target.value())))
            .filter(value -> !value.isBlank())
            .distinct()
            .collect(java.util.stream.Collectors.joining(" · "));
    if (visibleText.length() > 4_000) visibleText = visibleText.substring(0, 4_000);
    var focused =
        state.targets().stream()
            .filter(BrowserStateView.InteractiveTargetView::focused)
            .map(BrowserStateView.InteractiveTargetView::elementId)
            .findFirst()
            .orElse(null);
    var formControls =
        state.targets().stream()
            .filter(
                target ->
                    java.util.Set.of("textbox", "combobox", "checkbox", "radio", "button", "option")
                        .contains(target.role()))
            .map(BrowserStateView.InteractiveTargetView::elementId)
            .toList();
    var dialogs =
        state.targets().stream()
            .filter(
                target ->
                    java.util.Set.of("dialog", "alertdialog", "alert").contains(target.role()))
            .map(BrowserStateView.InteractiveTargetView::elementId)
            .toList();
    var visionRecommended =
        state.stateQuality().equals("DEPTH_LIMITED")
            || state.targets().stream().anyMatch(target -> target.visible() && target.occluded());
    var tabs =
        state.tabs().stream()
            .map(tab -> new TabView(tab.tabId(), tab.url(), tab.title(), tab.active()))
            .toList();
    var activeTab =
        tabs.stream()
            .filter(tab -> tab.active() && tab.tabId().equals(state.activeTabId()))
            .findFirst()
            .orElse(null);
    return new SnapshotView(
        cursor(state),
        state,
        visibleText,
        tabs,
        activeTab,
        focused,
        formControls,
        dialogs,
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
        visionRecommended);
  }

  public TargetListView inspect(String sessionId, String tenantId, InspectRequest request) {
    var snapshot = snapshot(sessionId, tenantId);
    requireCursor(snapshot, request.stateCursor());
    var requested = new LinkedHashSet<>(request.elementIds());
    var targets =
        snapshot.state().targets().stream()
            .filter(
                target ->
                    requested.contains(target.elementId())
                        || requested.contains(target.targetRef()))
            .toList();
    if (targets.size() != requested.size()) {
      throw new PerceptionException("ELEMENT_NOT_FOUND");
    }
    return new TargetListView(snapshot.stateCursor(), targets, false);
  }

  public TargetListView find(String sessionId, String tenantId, FindRequest request) {
    var snapshot = snapshot(sessionId, tenantId);
    var query = request.query().strip().toLowerCase(Locale.ROOT);
    var roles =
        request.roles().stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    var limit = request.limit() == null ? 20 : request.limit();
    var matching =
        snapshot.state().targets().stream()
            .filter(target -> request.includeHidden() || target.visible())
            .filter(
                target -> roles.isEmpty() || roles.contains(target.role().toLowerCase(Locale.ROOT)))
            .filter(target -> matches(target, query))
            .toList();
    return new TargetListView(
        snapshot.stateCursor(), matching.stream().limit(limit).toList(), matching.size() > limit);
  }

  private static boolean matches(BrowserStateView.InteractiveTargetView target, String query) {
    return java.util.stream.Stream.of(
            safe(target.elementId()),
            safe(target.role()),
            safe(target.name()),
            safe(target.controlType()),
            safe(target.visibilityReason()))
        .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(query));
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private static void requireCursor(SnapshotView snapshot, String expected) {
    if (!snapshot.stateCursor().equals(expected)) {
      throw new PerceptionException("STATE_CURSOR_STALE");
    }
  }

  private static void requireExecutable(BrowserStateView state) {
    if (!Set.of("COMPLETE", "DEPTH_LIMITED").contains(state.stateQuality())
        && !(state.stateQuality().equals("DEGRADED")
            && state.nativeDialogEvidenceFresh()
            && !state.nativeDialogs().isEmpty())) {
      throw new PerceptionException("BROWSER_STATE_NOT_EXECUTABLE");
    }
    if (state.tabs().isEmpty()) {
      if (!state.activeTabId().isEmpty()) {
        throw new PerceptionException("BROWSER_TAB_STATE_INVALID");
      }
      return;
    }
    if (state.tabs().stream().map(BrowserStateView.BrowserTabView::tabId).distinct().count()
            != state.tabs().size()
        || state.tabs().stream().filter(BrowserStateView.BrowserTabView::active).count() != 1
        || state.tabs().stream()
            .noneMatch(tab -> tab.active() && tab.tabId().equals(state.activeTabId()))) {
      throw new PerceptionException("BROWSER_TAB_STATE_INVALID");
    }
    if (state.nativeDialogs().stream()
                .map(BrowserStateView.NativeDialogView::dialogId)
                .distinct()
                .count()
            != state.nativeDialogs().size()
        || state.nativeDialogs().stream()
            .anyMatch(
                dialog ->
                    state.tabs().stream().noneMatch(tab -> tab.tabId().equals(dialog.tabId())))) {
      throw new PerceptionException("BROWSER_NATIVE_DIALOG_STATE_INVALID");
    }
  }

  private static String cursor(BrowserStateView state) {
    return state.stateVersion() + ":" + state.targetRevision() + ":" + state.stateHash();
  }

  public static final class PerceptionException extends RuntimeException {
    public PerceptionException(String message) {
      super(message);
    }
  }
}
