package io.browsercloud.api;

import io.browsercloud.domain.session.SessionState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class WorkspaceTagModels {
  private WorkspaceTagModels() {}

  public record WorkspaceTagRequest(
      @NotBlank @Size(max = 32) String name,
      @Size(max = 256) String description,
      @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color) {}

  public record WorkspaceTagSummary(String tagId, String name, String color) {}

  public record TagSessionView(
      String sessionId, String displayName, SessionState state, String region, Instant updatedAt) {}

  public record WorkspaceTagView(
      String tagId,
      String name,
      String description,
      String color,
      List<TagSessionView> sessions,
      int sessionCount,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {}

  public record WorkspaceTagListResponse(
      List<WorkspaceTagView> items, List<TagSessionView> sessions, int total) {}
}
