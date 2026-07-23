package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.CoordinatorResult;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.NodeEventReceived;
import io.browsercloud.coordinator.SessionCoordinator;
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

  private NodeEventIngestionService service;

  @BeforeEach
  void setUp() {
    service = new NodeEventIngestionService(inboxRepository, coordinator, browserStateRepository);
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

  private NodeEventReceived command() {
    return new NodeEventReceived(
        "evt-test",
        "tenant-test",
        "ses-test",
        0,
        0,
        1,
        1,
        new NodeEvent.RuntimeStopped("ses-test", "test", 0));
  }
}
