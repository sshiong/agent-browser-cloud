package io.browsercloud.application;

import static io.browsercloud.api.AgentBrowserPerceptionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.browsercloud.api.BrowserStateView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentBrowserPerceptionServiceTest {

  @Mock private SessionApplicationService sessions;
  private AgentBrowserPerceptionService service;

  @BeforeEach
  void setUp() {
    service = new AgentBrowserPerceptionService(sessions);
    when(sessions.getState("ses_1234567890abcdef", "tenant-test")).thenReturn(Optional.of(state()));
  }

  @Test
  void returnsOneStructuredSnapshotAndReusesItsCursorForInspect() {
    var snapshot = service.snapshot("ses_1234567890abcdef", "tenant-test");
    assertThat(snapshot.stateCursor()).isEqualTo("9:4:" + "a".repeat(64));
    assertThat(snapshot.state().targets().getFirst().elementId()).isEqualTo("e12");
    assertThat(snapshot.tabs()).extracting(TabView::tabId).containsExactly("tab-login");
    assertThat(snapshot.activeTab().tabId()).isEqualTo("tab-login");

    var inspected =
        service.inspect(
            "ses_1234567890abcdef",
            "tenant-test",
            new InspectRequest(snapshot.stateCursor(), List.of("e12")));
    assertThat(inspected.targets())
        .extracting(BrowserStateView.InteractiveTargetView::name)
        .containsExactly("Sign in");
  }

  @Test
  void rejectsStaleCursorBeforeReturningElementState() {
    assertThatThrownBy(
            () ->
                service.inspect(
                    "ses_1234567890abcdef",
                    "tenant-test",
                    new InspectRequest("8:4:" + "b".repeat(64), List.of("e12"))))
        .isInstanceOf(AgentBrowserPerceptionService.PerceptionException.class)
        .hasMessage("STATE_CURSOR_STALE");
  }

  @Test
  void findDefaultsToVisibleTargetsAndCanExplainHiddenMatches() {
    var visible =
        service.find(
            "ses_1234567890abcdef", "tenant-test", new FindRequest("button", List.of(), false, 20));
    assertThat(visible.targets())
        .extracting(BrowserStateView.InteractiveTargetView::elementId)
        .containsExactly("e12");

    var hidden =
        service.find(
            "ses_1234567890abcdef", "tenant-test", new FindRequest("hidden", List.of(), true, 20));
    assertThat(hidden.targets())
        .singleElement()
        .extracting(BrowserStateView.InteractiveTargetView::visibilityReason)
        .isEqualTo("DISPLAY_NONE");
  }

  private static BrowserStateView state() {
    return new BrowserStateView(
        "ses_1234567890abcdef",
        2,
        9,
        4,
        "https://example.test/login",
        "Login",
        "a".repeat(64),
        "COMPLETE",
        "complete",
        1_000,
        true,
        List.of(
            target("target:4:one", "e12", "button", "Sign in", true, null),
            target("target:4:two", "e13", "button", "Hidden action", false, "DISPLAY_NONE")),
        List.of(
            new BrowserStateView.BrowserTabView(
                "tab-login", "https://example.test/login", "Login", true)),
        "tab-login");
  }

  private static BrowserStateView.InteractiveTargetView target(
      String targetRef,
      String elementId,
      String role,
      String name,
      boolean visible,
      String visibilityReason) {
    return new BrowserStateView.InteractiveTargetView(
        targetRef,
        elementId,
        role,
        name,
        null,
        "button",
        new BrowserStateView.BoundsView(10, 10, 80, 24),
        true,
        visible,
        false,
        false,
        null,
        null,
        true,
        "main",
        visible,
        false,
        visibilityReason);
  }
}
