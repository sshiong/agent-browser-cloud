package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.CoordinatorResult;
import io.browsercloud.coordinator.NodeCommandGateway;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.SessionCoordinator;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.persistence.InboxEventJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeEventIngestionServiceTest {

  @Mock private InboxEventJpaRepository inboxRepository;
  @Mock private SessionCoordinator coordinator;
  @Mock private BrowserStateRepository browserStateRepository;
  @Mock private ProfileApplicationService profileApplicationService;
  @Mock private StaticProxyApplicationService proxyApplicationService;
  @Mock private SessionRepository sessionRepository;
  @Mock private NodeCommandGateway nodeCommandGateway;

  private NodeEventIngestionService service;

  @BeforeEach
  void setUp() {
    service =
        new NodeEventIngestionService(
            inboxRepository,
            coordinator,
            browserStateRepository,
            profileApplicationService,
            proxyApplicationService,
            sessionRepository,
            nodeCommandGateway);
  }

  @Test
  void shouldAcknowledgeDuplicateWithoutReapplyingCoordinatorState() {
    var command = command();
    when(inboxRepository.existsById(command.eventId())).thenReturn(true);

    var receipt = service.receive(command);

    assertThat(receipt.duplicate()).isTrue();
    verify(coordinator, never()).handle(any());
  }

  @Test
  void shouldRecordInboxOnlyAfterCoordinatorCompletes() {
    var command = command();
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());

    var receipt = service.receive(command);

    assertThat(receipt.duplicate()).isFalse();
    verify(profileApplicationService)
        .recordCheckpoint("tenant-test", (NodeEvent.RuntimeStopped) command.event());
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldNotRecordRejectedEvent() {
    var command = command();
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.rejected("STALE_CONTEXT_EPOCH"));

    assertThatThrownBy(() -> service.receive(command))
        .isInstanceOf(NodeEventIngestionService.NodeEventRejectedException.class)
        .hasMessageContaining("STALE_CONTEXT_EPOCH");
    verify(inboxRepository, never()).save(any());
  }

  @Test
  void shouldPersistAcceptedBrowserStateBeforeAcknowledgingInbox() {
    var state =
        new NodeEvent.StateUpdated(
            "ses-test",
            4,
            4,
            "https://example.test",
            "Example",
            "hash-4",
            "COMPLETE",
            java.util.List.of());
    var command = new NodeEventReceived("evt-state", "tenant-test", "ses-test", 0, 2, 0, 2, state);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());

    service.receive(command);

    verify(browserStateRepository).save("tenant-test", 2, state);
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldApplyDiffAgainstTheDeclaredBaseVersion() {
    var diff =
        new NodeEvent.StateDiff(
            "ses-test",
            4,
            5,
            2,
            "https://example.test",
            "Changed",
            "hash-5",
            "COMPLETE",
            java.util.List.of(),
            java.util.List.of("target:2:old"));
    var command = new NodeEventReceived("evt-diff", "tenant-test", "ses-test", 0, 2, 0, 3, diff);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());
    when(browserStateRepository.applyDiff("tenant-test", 2, diff)).thenReturn(true);

    service.receive(command);

    verify(browserStateRepository).applyDiff("tenant-test", 2, diff);
    verify(inboxRepository).save(any());
  }

  @Test
  void shouldInvalidateStateWhenDiffBaseCannotBeApplied() {
    var diff =
        new NodeEvent.StateDiff(
            "ses-test",
            4,
            6,
            2,
            "https://example.test",
            "Gap",
            "hash-6",
            "COMPLETE",
            java.util.List.of(),
            java.util.List.of());
    var command = new NodeEventReceived("evt-gap", "tenant-test", "ses-test", 0, 2, 0, 4, diff);
    when(coordinator.handle(command)).thenReturn(CoordinatorResult.completed());
    when(browserStateRepository.applyDiff("tenant-test", 2, diff)).thenReturn(false);
    when(sessionRepository.require("ses-test"))
        .thenReturn(
            new io.browsercloud.domain.session.SessionContext(
                "ses-test",
                "tenant-test",
                "profile-test",
                "node-test",
                "runtime-test",
                null,
                null,
                0,
                2,
                1,
                0,
                io.browsercloud.domain.session.ResourceClass.L2,
                io.browsercloud.domain.session.SessionState.RUNNING,
                "",
                java.time.Instant.EPOCH,
                java.time.Instant.EPOCH));

    service.receive(command);

    verify(browserStateRepository)
        .invalidate("tenant-test", 2, "ses-test", 6, "BASE_VERSION_MISMATCH");
    verify(nodeCommandGateway).send(any());
    verify(inboxRepository).save(any());
  }

  private NodeEventReceived command() {
    return new NodeEventReceived(
        "evt-test",
        "tenant-test",
        "ses-test",
        0,
        0,
        1,
        1,
        new NodeEvent.RuntimeStopped(
            "ses-test", "test", 0, "profile-test", "chk-test", 1, 1, 0, 0, "EMPTY"));
  }
}
