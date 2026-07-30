package io.browsercloud.application;

import static io.browsercloud.api.WorkspaceNotificationModels.*;

import io.browsercloud.application.WorkspaceNotificationStore.StoredNotification;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Tenant-safe notification feed backed by the immutable audit-event projection. */
@Service
public class WorkspaceNotificationApplicationService {

  private static final Map<String, String> SUBJECTS =
      Map.ofEntries(
          Map.entry("BREAK_GLASS", "紧急访问"),
          Map.entry("KEY_ROTATION", "密钥轮换"),
          Map.entry("RUNTIME_RELEASE", "Runtime 发布"),
          Map.entry("RECOVERY_CONTRACT", "恢复契约"),
          Map.entry("EVIDENCE_ACCESS", "证据访问"),
          Map.entry("SECURE_DEBUG", "安全调试"),
          Map.entry("PROFILE_IMPORT", "Profile 导入"),
          Map.entry("ENVIRONMENT_IMPORT", "环境导入"),
          Map.entry("MIGRATION", "Session 迁移"),
          Map.entry("RESOURCE", "资源策略"),
          Map.entry("HUMAN_CONFIRMATION", "人工确认"),
          Map.entry("HUMAN_HANDOFF", "人工接管"),
          Map.entry("AGENT", "Agent 任务"));

  private final WorkspaceNotificationStore store;
  private final Clock clock;

  @Autowired
  public WorkspaceNotificationApplicationService(WorkspaceNotificationStore store) {
    this(store, Clock.systemUTC());
  }

  WorkspaceNotificationApplicationService(WorkspaceNotificationStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public WorkspaceNotificationListResponse list(
      String tenantId, String actorId, int requestedLimit, Long beforeSequence) {
    int limit = Math.max(1, Math.min(requestedLimit, 50));
    if (beforeSequence != null && beforeSequence < 1) {
      throw new IllegalArgumentException("Notification cursor must be positive");
    }
    var snapshot = store.snapshot(tenantId, actorId, limit, beforeSequence, clock.instant());
    var items =
        snapshot.items().stream()
            .map(item -> toView(item, item.sequence() <= snapshot.lastReadSequence()))
            .toList();
    return new WorkspaceNotificationListResponse(
        items,
        snapshot.unreadCount(),
        snapshot.lastReadSequence(),
        snapshot.headSequence(),
        snapshot.nextBeforeSequence());
  }

  public WorkspaceNotificationReadState markRead(
      String tenantId, String actorId, long readThroughSequence) {
    var state = store.markRead(tenantId, actorId, readThroughSequence, clock.instant());
    return new WorkspaceNotificationReadState(
        state.lastReadSequence(), state.unreadCount(), state.updatedAt());
  }

  private WorkspaceNotificationView toView(StoredNotification item, boolean read) {
    var subject = subject(item.eventType());
    return new WorkspaceNotificationView(
        item.notificationId(),
        item.sequence(),
        item.category(),
        item.severity(),
        title(item, subject),
        body(item, subject),
        item.eventType(),
        item.sessionId(),
        item.resourceType(),
        item.resourceId(),
        item.requestId(),
        route(item),
        read,
        item.occurredAt());
  }

  private static String subject(String eventType) {
    var normalized = eventType.toUpperCase(Locale.ROOT);
    return SUBJECTS.entrySet().stream()
        .filter(entry -> normalized.contains(entry.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse("平台操作");
  }

  private static String title(StoredNotification item, String subject) {
    var eventType = item.eventType();
    var action = item.action();
    var result = item.result().toUpperCase(Locale.ROOT);
    if (item.severity() == NotificationSeverity.CRITICAL) {
      return subject + "执行失败";
    }
    if (eventType.endsWith("_REQUESTED") || action.endsWith("_REQUESTED")) {
      return subject + "等待处理";
    }
    if (eventType.endsWith("_APPROVED")
        || action.endsWith("_APPROVED")
        || "APPROVED".equals(result)) {
      return subject + "已批准";
    }
    if (eventType.endsWith("_COMPLETED")
        || action.endsWith("_COMPLETED")
        || "COMPLETED".equals(result)) {
      return subject + "已完成";
    }
    if (result.matches("REJECTED|DENIED|ABORTED")) {
      return subject + "未获执行";
    }
    if (result.matches("EXPIRED|REVOKED")) {
      return subject + "已失效";
    }
    if (eventType.startsWith("MAXIMUM_")
        || action.startsWith("MAXIMUM_")
        || "PAUSED_BY_RESOURCE_POLICY".equals(eventType)
        || "PAUSED_BY_RESOURCE_POLICY".equals(action)) {
      return "资源策略需要关注";
    }
    return subject + "状态已更新";
  }

  private static String body(StoredNotification item, String subject) {
    var reference =
        item.sessionId() != null
            ? item.sessionId()
            : item.resourceId() != null ? item.resourceId() : "workspace";
    return subject + " · " + item.result() + " · " + reference;
  }

  private static String route(StoredNotification item) {
    if (item.sessionId() != null) {
      return "/environments/" + item.sessionId();
    }
    return switch (item.category()) {
      case SECURITY -> "/security";
      case RESOURCE -> "/nodes";
      case AGENT -> "/automation";
      case RELEASE -> item.eventType().startsWith("RUNTIME_") ? "/runtimes" : "/enterprise";
      case SYSTEM ->
          switch (item.resourceType() == null ? "" : item.resourceType()) {
            case "PROFILE" -> "/profiles";
            case "WORKSPACE_GROUP", "WORKSPACE_TAG" -> "/groups";
            default -> "/overview";
          };
    };
  }
}
