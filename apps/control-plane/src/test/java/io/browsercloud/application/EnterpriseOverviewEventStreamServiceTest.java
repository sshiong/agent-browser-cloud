package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.persistence.EnterpriseOverviewStreamStore;
import io.browsercloud.persistence.EnterpriseOverviewStreamStore.DurableEnterpriseOverviewChange;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnterpriseOverviewEventStreamServiceTest {
  private final EnterpriseOverviewStreamStore store = mock(EnterpriseOverviewStreamStore.class);

  @Test
  void resumesTenantAndGlobalOverviewChangesFromTheDurableCursor() {
    when(store.latestSequence("tenant-a")).thenReturn(12L);
    when(store.readAfter("tenant-a", 4L, 500))
        .thenReturn(
            List.of(
                new DurableEnterpriseOverviewChange(
                    5L, "MEDIA_QUOTA", Instant.parse("2026-08-18T00:00:00Z")),
                new DurableEnterpriseOverviewChange(
                    9L, "REGION", Instant.parse("2026-08-18T00:00:01Z"))));
    var service = new EnterpriseOverviewEventStreamService(store, 10, 2, 60_000);

    service.subscribe("tenant-a", "4");
    service.publishDurableChanges();

    verify(store, atLeastOnce()).readAfter("tenant-a", 4L, 500);
    assertThat(service.activeSubscriberCount()).isEqualTo(1);
  }

  @Test
  void isolatesTenantChannelsAndStartsNewSubscribersAtTheCurrentCursor() {
    when(store.latestSequence("tenant-a")).thenReturn(15L);
    when(store.latestSequence("tenant-b")).thenReturn(7L);
    when(store.readAfter("tenant-a", 15L, 500)).thenReturn(List.of());
    when(store.readAfter("tenant-b", 7L, 500)).thenReturn(List.of());
    var service = new EnterpriseOverviewEventStreamService(store, 10, 2, 60_000);

    service.subscribe("tenant-a", null);
    service.subscribe("tenant-b", null);
    service.publishDurableChanges();

    verify(store, atLeastOnce()).readAfter("tenant-a", 15L, 500);
    verify(store, atLeastOnce()).readAfter("tenant-b", 7L, 500);
    assertThat(service.activeSubscriberCount()).isEqualTo(2);
  }

  @Test
  void resetsFutureCursorsAndBoundsTenantSubscribers() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> EnterpriseOverviewEventStreamService.parseCursor("cursor-a"));
    when(store.latestSequence("tenant-a")).thenReturn(8L);
    when(store.readAfter("tenant-a", 8L, 500)).thenReturn(List.of());
    var service = new EnterpriseOverviewEventStreamService(store, 10, 1, 60_000);

    service.subscribe("tenant-a", "99");

    assertThatThrownBy(() -> service.subscribe("tenant-a", null))
        .isInstanceOf(SessionResourceEventStreamService.ResourceStreamCapacityException.class);
    verify(store, atLeastOnce()).readAfter("tenant-a", 8L, 500);
  }
}
