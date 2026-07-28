package io.browsercloud.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class WorkspaceSettingsModels {
  private WorkspaceSettingsModels() {}

  public record WorkspaceSettingsRequest(
      @NotBlank @Size(max = 96) String workspaceName,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String defaultRuntimeBuildId,
      @NotBlank @Pattern(regexp = "^[a-z0-9-]{1,32}$") String defaultRegion,
      boolean defaultHumanTakeoverEnabled) {}

  public record WorkspaceSettingsView(
      String workspaceName,
      String defaultRuntimeBuildId,
      String defaultRegion,
      boolean defaultHumanTakeoverEnabled,
      String resourcePolicyMode,
      String onMaximumReached,
      String source,
      String updatedBy,
      Instant updatedAt,
      long version) {}
}
