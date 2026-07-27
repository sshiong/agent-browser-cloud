package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.SessionResourceStreamStore;
import io.browsercloud.persistence.SessionResourceStreamStore.DurableResourceChange;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionResourceEventStreamServiceTest {

  private static final String SESSION_ID = "ses_1234567890abcdef";
  private final SessionRepository sessions = mock(SessionRepository.class);
  private final SessionResourceStreamStore store = mock(SessionResourceStreamStore.class);

  @BeforeEach
  void setUp() {
    when(sessions.require(SESSION_ID)).thenReturn(session());
  }

  @Test
  void resumesFromLastEventIdAndPublishesOnlyDurableChanges() {
    when(store.latestSequence("tenant-a", SESSION_ID)).thenReturn(10L);
    when(store.readAfter("tenant-a", SESSION_ID, 4L, 500))
        .thenReturn(
            List.of(
                new DurableResourceChange(
                    5L, "RESOURCE_SAMPLE", "rs_123", Instant.parse("2026-07-28T00:00:00Z"))));
    var service = new SessionResourceEventStreamService(sessions, store, 10, 2, 60_000);

    service.subscribe(SESSION_ID, "tenant-a", "4");
    service.publishDurableChanges();

    verify(store).readAfter("tenant-a", SESSION_ID, 4L, 500);
    assertThat(service.activeSubscriberCount()).isEqualTo(1);
  }

  @Test
  void startsAtCurrentCursorWithoutReplayingHistoryForANewSubscriber() {
    when(store.latestSequence("tenant-a", SESSION_ID)).thenReturn(12L);
    when(store.readAfter("tenant-a", SESSION_ID, 12L, 500)).thenReturn(List.of());
    var service = new SessionResourceEventStreamService(sessions, store, 10, 2, 60_000);

    service.subscribe(SESSION_ID, "tenant-a", null);
    service.publishDurableChanges();

    verify(store, atLeastOnce()).readAfter("tenant-a", SESSION_ID, 12L, 500);
  }

  @Test
  void rejectsInvalidCursorAndBoundsSubscribersPerSession() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SessionResourceEventStreamService.parseCursor("other-session"));
    when(store.latestSequence("tenant-a", SESSION_ID)).thenReturn(0L);
    var service = new SessionResourceEventStreamService(sessions, store, 1, 1, 60_000);

    service.subscribe(SESSION_ID, "tenant-a", null);

    assertThatThrownBy(() -> service.subscribe(SESSION_ID, "tenant-a", null))
        .isInstanceOf(SessionResourceEventStreamService.ResourceStreamCapacityException.class);
  }

  private static SessionContext session() {
    var now = Instant.now();
    return new SessionContext(
        SESSION_ID,
        "tenant-a",
        "profile-a",
        "node-a",
        "runtime-a",
        "isolation-a",
        "proxy-a",
        1,
        1,
        1,
        1,
        ResourceClass.L2,
        SessionState.RUNNING,
        "policy-hash",
        now,
        now);
  }
}
