package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.persistence.WorkspaceNotificationStreamStore;
import io.browsercloud.persistence.WorkspaceNotificationStreamStore.DurableNotificationChange;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceNotificationEventStreamServiceTest {
  private final WorkspaceNotificationStreamStore store =
      mock(WorkspaceNotificationStreamStore.class);

  @Test
  void resumesTenantNotificationsFromTheImmutableAuditCursor() {
    when(store.latestSequence("tenant-a")).thenReturn(12L);
    when(store.readAfter("tenant-a", 4L, 500))
        .thenReturn(
            List.of(
                new DurableNotificationChange(5L, Instant.parse("2026-08-08T00:00:00Z")),
                new DurableNotificationChange(9L, Instant.parse("2026-08-08T00:00:01Z"))));
    var service = new WorkspaceNotificationEventStreamService(store, 10, 2, 60_000);

    service.subscribe("tenant-a", "4");
    service.publishDurableChanges();

    verify(store, atLeastOnce()).readAfter("tenant-a", 4L, 500);
    assertThat(service.activeSubscriberCount()).isEqualTo(1);
  }

  @Test
  void startsNewSubscribersAtTheCurrentCursorWithoutHistoricalReplay() {
    when(store.latestSequence("tenant-a")).thenReturn(15L);
    when(store.readAfter("tenant-a", 15L, 500)).thenReturn(List.of());
    var service = new WorkspaceNotificationEventStreamService(store, 10, 2, 60_000);

    service.subscribe("tenant-a", null);
    service.publishDurableChanges();

    verify(store, atLeastOnce()).readAfter("tenant-a", 15L, 500);
  }

  @Test
  void resetsFutureCursorsAndBoundsTenantSubscribers() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> WorkspaceNotificationEventStreamService.parseCursor("cursor-a"));
    when(store.latestSequence("tenant-a")).thenReturn(8L);
    when(store.readAfter("tenant-a", 8L, 500)).thenReturn(List.of());
    var service = new WorkspaceNotificationEventStreamService(store, 10, 1, 60_000);

    service.subscribe("tenant-a", "99");

    assertThatThrownBy(() -> service.subscribe("tenant-a", null))
        .isInstanceOf(SessionResourceEventStreamService.ResourceStreamCapacityException.class);
    verify(store, atLeastOnce()).readAfter("tenant-a", 8L, 500);
  }

  @Test
  void keepsChannelRegisteredWhenPublishingInterleavesWithFirstCursorLookup() {
    var service = new WorkspaceNotificationEventStreamService(store, 10, 2, 60_000);
    when(store.latestSequence("tenant-a"))
        .thenAnswer(
            ignored -> {
              service.publishDurableChanges();
              return 10L;
            });
    when(store.readAfter("tenant-a", 10L, 500)).thenReturn(List.of());
    service.subscribe("tenant-a", "10");
    clearInvocations(store);
    when(store.readAfter("tenant-a", 10L, 500))
        .thenReturn(List.of(new DurableNotificationChange(11L, Instant.now())));

    service.publishDurableChanges();
    verify(store).readAfter("tenant-a", 10L, 500);
    assertThat(service.activeSubscriberCount()).isEqualTo(1);
  }

  @Test
  void doesNotAdvanceNewReplaySubscriberUsingAnOlderSubscribersInFlightRead() {
    var service = new WorkspaceNotificationEventStreamService(store, 10, 2, 60_000);
    when(store.latestSequence("tenant-a")).thenReturn(10L);
    when(store.readAfter("tenant-a", 10L, 500)).thenReturn(List.of());
    when(store.readAfter("tenant-a", 4L, 500)).thenReturn(List.of());
    service.subscribe("tenant-a", "10");
    when(store.readAfter("tenant-a", 10L, 500))
        .thenAnswer(
            ignored -> {
              service.subscribe("tenant-a", "4");
              return List.of(new DurableNotificationChange(11L, Instant.now()));
            });
    service.publishDurableChanges();
    clearInvocations(store);

    service.publishDurableChanges();
    verify(store).readAfter("tenant-a", 4L, 500);
    assertThat(service.activeSubscriberCount()).isEqualTo(2);
  }
}
