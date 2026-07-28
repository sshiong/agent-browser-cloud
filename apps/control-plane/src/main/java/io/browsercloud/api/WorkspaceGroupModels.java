package io.browsercloud.api;

import io.browsercloud.domain.resource.MaximumReachedPolicy;
import io.browsercloud.domain.session.SessionState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class WorkspaceGroupModels {
  private WorkspaceGroupModels() {}

  public record WorkspaceGroupRequest(
      @NotBlank @Size(max = 96) String name,
      @Size(max = 512) String description,
      @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
      @NotNull MaximumReachedPolicy defaultOnMaximumReached,
      @NotNull Boolean defaultAllowMigration,
      @NotNull Boolean defaultAllowHibernate) {}

  public record GroupSessionView(
      String sessionId, String displayName, SessionState state, String region, Instant updatedAt) {}

  public record WorkspaceGroupView(
      String groupId,
      String name,
      String description,
      String color,
      MaximumReachedPolicy defaultOnMaximumReached,
      boolean defaultAllowMigration,
      boolean defaultAllowHibernate,
      List<GroupSessionView> sessions,
      int sessionCount,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {}

  public record WorkspaceGroupListResponse(
      List<WorkspaceGroupView> items, List<GroupSessionView> unassignedSessions, int total) {}
}
