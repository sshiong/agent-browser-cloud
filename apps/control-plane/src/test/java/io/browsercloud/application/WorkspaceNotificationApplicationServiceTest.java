package io.browsercloud.application;

import static io.browsercloud.api.WorkspaceNotificationModels.NotificationCategory;
import static io.browsercloud.api.WorkspaceNotificationModels.NotificationSeverity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.application.WorkspaceNotificationStore.NotificationSnapshot;
import io.browsercloud.application.WorkspaceNotificationStore.StoredNotification;
import io.browsercloud.application.WorkspaceNotificationStore.StoredReadState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceNotificationApplicationServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-31T08:00:00Z");

  @Mock private WorkspaceNotificationStore store;

  private WorkspaceNotificationApplicationService service;

  @BeforeEach
  void setUp() {
    service = new WorkspaceNotificationApplicationService(store, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void mapsAuthoritativeAuditProjectionToActorScopedUnreadFeed() {
    var failedMigration =
        new StoredNotification(
            "ntf_migration",
            42,
            "ses_1234567890abcdef",
            "MIGRATION_RECONCILIATION_FAILED",
            NotificationCategory.RESOURCE,
            NotificationSeverity.CRITICAL,
            "SESSION",
            "ses_1234567890abcdef",
            "MIGRATION",
            "FAILED",
            "req_migration",
            NOW.minusSeconds(10));
    var approval =
        new StoredNotification(
            "ntf_release",
            40,
            null,
            "RUNTIME_RELEASE_REQUESTED",
            NotificationCategory.RELEASE,
            NotificationSeverity.WARNING,
            "RUNTIME_RELEASE",
            "rel_123",
            "REQUEST_RELEASE",
            "REQUESTED",
            "req_release",
            NOW.minusSeconds(20));
    when(store.snapshot("tenant-a", "operator-a", 30, null, NOW))
        .thenReturn(new NotificationSnapshot(List.of(failedMigration, approval), 1, 40, 42, null));

    var response = service.list("tenant-a", "operator-a", 30, null);

    assertThat(response.unreadCount()).isEqualTo(1);
    assertThat(response.items()).extracting("read").containsExactly(false, true);
    assertThat(response.items().getFirst().title()).isEqualTo("Session 迁移执行失败");
    assertThat(response.items().getFirst().route()).isEqualTo("/environments/ses_1234567890abcdef");
    assertThat(response.items().getLast().title()).isEqualTo("Runtime 发布等待处理");
    assertThat(response.items().getLast().route()).isEqualTo("/runtimes");
  }

  @Test
  void advancesOnlyThroughTheStoreCommittedMonotonicCursor() {
    when(store.markRead("tenant-a", "operator-a", 44, NOW))
        .thenReturn(new StoredReadState(44, 2, NOW));

    var state = service.markRead("tenant-a", "operator-a", 44);

    assertThat(state.lastReadSequence()).isEqualTo(44);
    assertThat(state.unreadCount()).isEqualTo(2);
    assertThat(state.updatedAt()).isEqualTo(NOW);
    verify(store).markRead("tenant-a", "operator-a", 44, NOW);
  }

  @Test
  void rejectsNonPositivePaginationCursorBeforeQueryingPostgres() {
    assertThatThrownBy(() -> service.list("tenant-a", "operator-a", 30, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }
}
