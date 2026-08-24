package io.browsercloud.application;

import static io.browsercloud.api.AgentBrowserScreenshotModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.browsercloud.api.AgentBrowserPerceptionModels.SnapshotView;
import io.browsercloud.api.BrowserStateView;
import io.browsercloud.coordinator.NodeCommand;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.proto.node.v1.CaptureAgentScreenshotCommand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentBrowserScreenshotApplicationServiceTest {
  private static final String SESSION_ID = "ses_1234567890abcdef";
  private static final String CURSOR = "9:4:" + "a".repeat(64);

  @Mock private SessionRepository sessions;
  @Mock private BrowserCapacityApplicationService capacity;
  @Mock private AgentBrowserPerceptionService perception;
  @Mock private AgentBrowserScreenshotStore store;
  @Mock private NodeCommandGateway commands;
  @Mock private SessionEvidenceGovernanceService evidence;
  @Mock private AuditApplicationService audit;
  private AgentBrowserScreenshotApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new AgentBrowserScreenshotApplicationService(
            sessions, capacity, perception, store, commands, evidence, audit);
  }

  @Test
  void persistsAndDispatchesAnExactActiveTabRegionWithoutReturningPixels() throws Exception {
    var session = runningSession();
    var view = mock(ScreenshotView.class);
    when(sessions.requireForUpdate(SESSION_ID)).thenReturn(session);
    when(capacity.nodeHasCapability("node-test", "agentScreenshot", "state-fenced-region-v1"))
        .thenReturn(true);
    when(perception.snapshot(SESSION_ID, "tenant-test")).thenReturn(snapshot(false));
    when(store.findIdentityByIdempotency("tenant-test", "agent-worker", "idem-shot-1"))
        .thenReturn(Optional.empty());
    when(store.insert(any())).thenReturn(true);
    when(store.find(eq("tenant-test"), eq(SESSION_ID), anyString(), eq("agent-worker")))
        .thenReturn(Optional.of(view));
    var request =
        new CaptureScreenshotRequest(
            ScreenshotMode.REGION, CURSOR, null, new ScreenshotRegion(10, 20, 300, 180));

    var result =
        service.capture(
            SESSION_ID, "tenant-test", "agent-worker", "idem-shot-1", "request-test", request);

    assertThat(result).isSameAs(view);
    var persisted = ArgumentCaptor.forClass(AgentBrowserScreenshotStore.RequestRecord.class);
    verify(store).insert(persisted.capture());
    assertThat(persisted.getValue().elementId()).isNull();
    assertThat(persisted.getValue().region()).isEqualTo(new ScreenshotRegion(10, 20, 300, 180));
    var sent = ArgumentCaptor.forClass(NodeCommand.class);
    verify(commands).send(sent.capture());
    assertThat(sent.getValue().commandType()).isEqualTo("CaptureAgentScreenshot");
    var payload = CaptureAgentScreenshotCommand.parseFrom(sent.getValue().payload());
    assertThat(payload.getCaptureMode()).isEqualTo("REGION");
    assertThat(payload.getActiveTabId()).isEqualTo("tab-login");
    assertThat(payload.getBaseStateVersion()).isEqualTo(9);
    assertThat(payload.getTargetRevision()).isEqualTo(4);
    assertThat(payload.getRegionWidth()).isEqualTo(300);
    verify(audit).append(any());
  }

  @Test
  void rejectsAStaleCursorBeforePersistenceOrNodeDispatch() {
    var session = runningSession();
    when(sessions.requireForUpdate(SESSION_ID)).thenReturn(session);
    when(capacity.nodeHasCapability("node-test", "agentScreenshot", "state-fenced-region-v1"))
        .thenReturn(true);
    when(perception.snapshot(SESSION_ID, "tenant-test")).thenReturn(snapshot(false));

    assertThatThrownBy(
            () ->
                service.capture(
                    SESSION_ID,
                    "tenant-test",
                    "agent-worker",
                    "idem-shot-2",
                    "request-test",
                    new CaptureScreenshotRequest(
                        ScreenshotMode.VIEWPORT, "8:4:" + "b".repeat(64), null, null)))
        .isInstanceOf(
            AgentBrowserScreenshotApplicationService.AgentBrowserScreenshotException.class)
        .hasMessage("STATE_CURSOR_STALE");
    verifyNoInteractions(store, commands, audit);
  }

  @Test
  void rejectsAnOccludedElementBeforeCreatingEvidence() {
    var session = runningSession();
    when(sessions.requireForUpdate(SESSION_ID)).thenReturn(session);
    when(capacity.nodeHasCapability("node-test", "agentScreenshot", "state-fenced-region-v1"))
        .thenReturn(true);
    when(perception.snapshot(SESSION_ID, "tenant-test")).thenReturn(snapshot(true));

    assertThatThrownBy(
            () ->
                service.capture(
                    SESSION_ID,
                    "tenant-test",
                    "agent-worker",
                    "idem-shot-3",
                    "request-test",
                    new CaptureScreenshotRequest(ScreenshotMode.ELEMENT, CURSOR, "e12", null)))
        .isInstanceOf(
            AgentBrowserScreenshotApplicationService.AgentBrowserScreenshotException.class)
        .hasMessage("ELEMENT_OCCLUDED");
    verifyNoInteractions(store, commands, audit);
  }

  private static SnapshotView snapshot(boolean occluded) {
    var state =
        new BrowserStateView(
            SESSION_ID,
            2,
            9,
            4,
            "https://example.test/login",
            "Login",
            "a".repeat(64),
            "COMPLETE",
            "complete",
            1000,
            true,
            List.of(
                new BrowserStateView.InteractiveTargetView(
                    "target:4:one",
                    "e12",
                    "button",
                    "Sign in",
                    null,
                    "button",
                    new BrowserStateView.BoundsView(10, 10, 80, 24),
                    true,
                    true,
                    false,
                    false,
                    null,
                    null,
                    true,
                    "main",
                    true,
                    occluded,
                    occluded ? "OCCLUDED" : null)),
            List.of(
                new BrowserStateView.BrowserTabView(
                    "tab-login", "https://example.test/login", "Login", true)),
            "tab-login");
    return new SnapshotView(CURSOR, state);
  }

  private static SessionContext runningSession() {
    var now = Instant.parse("2026-08-24T00:00:00Z");
    return new SessionContext(
        SESSION_ID,
        "tenant-test",
        "profile-test",
        "node-test",
        "runtime-stable",
        "isolation-standard",
        "proxy-test",
        3,
        2,
        1,
        1,
        ResourceClass.L2,
        SessionState.RUNNING,
        "policy-hash",
        now,
        now);
  }
}
