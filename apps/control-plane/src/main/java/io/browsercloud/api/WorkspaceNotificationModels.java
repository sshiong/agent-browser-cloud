package io.browsercloud.api;

import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;

public final class WorkspaceNotificationModels {
  private WorkspaceNotificationModels() {}

  public enum NotificationCategory {
    SECURITY,
    RESOURCE,
    AGENT,
    RELEASE,
    SYSTEM
  }

  public enum NotificationSeverity {
    INFO,
    WARNING,
    CRITICAL
  }

  public record WorkspaceNotificationView(
      String notificationId,
      long sequence,
      NotificationCategory category,
      NotificationSeverity severity,
      String title,
      String body,
      String eventType,
      String sessionId,
      String resourceType,
      String resourceId,
      String requestId,
      String route,
      boolean read,
      Instant occurredAt) {}

  public record WorkspaceNotificationListResponse(
      List<WorkspaceNotificationView> items,
      long unreadCount,
      long lastReadSequence,
      long headSequence,
      Long nextBeforeSequence) {
    public WorkspaceNotificationListResponse {
      items = List.copyOf(items);
    }
  }

  public record UpdateNotificationReadCursorRequest(@Min(0) long readThroughSequence) {}

  public record WorkspaceNotificationReadState(
      long lastReadSequence, long unreadCount, Instant updatedAt) {}

  public record WorkspaceNotificationStreamControl(
      long cursor, boolean resetRequired, Instant connectedAt) {}

  public record WorkspaceNotificationStreamEvent(
      long sequence, Instant occurredAt, boolean replayed) {}
}
