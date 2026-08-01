package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.persistence.WorkspaceOverviewStreamStore;
import io.browsercloud.persistence.WorkspaceOverviewStreamStore.DurableWorkspaceChange;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceOverviewEventStreamServiceTest {
  private final WorkspaceOverviewStreamStore store = mock(WorkspaceOverviewStreamStore.class);

  @Test
  void resumesTenantAndGlobalChangesFromTheDurableCursor() {
    when(store.latestSequence("tenant-a", true)).thenReturn(12L);
    when(store.readAfter("tenant-a", true, 4L, 500))
        .thenReturn(
            List.of(
                new DurableWorkspaceChange(
                    5L, "BROWSER_NODE", Instant.parse("2026-08-01T00:00:00Z")),
                new DurableWorkspaceChange(6L, "SESSION", Instant.parse("2026-08-01T00:00:01Z"))));
    var service = new WorkspaceOverviewEventStreamService(store, 10, 2, 60_000);

    service.subscribe("tenant-a", true, "4");
    service.publishDurableChanges();

    verify(store, atLeastOnce()).readAfter("tenant-a", true, 4L, 500);
    assertThat(service.activeSubscriberCount()).isEqualTo(1);
  }

  @Test
  void startsNewSubscribersAtTheCurrentCursorWithoutHistoricalReplay() {
    when(store.latestSequence("tenant-a", false)).thenReturn(15L);
    when(store.readAfter("tenant-a", false, 15L, 500)).thenReturn(List.of());
    var service = new WorkspaceOverviewEventStreamService(store, 10, 2, 60_000);

    service.subscribe("tenant-a", false, null);
    service.publishDurableChanges();

    verify(store, atLeastOnce()).readAfter("tenant-a", false, 15L, 500);
  }

  @Test
  void rejectsInvalidCursorsAndBoundsTenantSubscribers() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> WorkspaceOverviewEventStreamService.parseCursor("cursor-a"));
    when(store.latestSequence("tenant-a", false)).thenReturn(0L);
    when(store.latestSequence("tenant-a", true)).thenReturn(0L);
    var service = new WorkspaceOverviewEventStreamService(store, 10, 1, 60_000);

    service.subscribe("tenant-a", false, null);

    assertThatThrownBy(() -> service.subscribe("tenant-a", true, null))
        .isInstanceOf(SessionResourceEventStreamService.ResourceStreamCapacityException.class);
  }
}
