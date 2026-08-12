package io.browsercloud.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
      boolean defaultHumanTakeoverEnabled,
      @Min(250) @Max(100000) Integer remoteDesktopControlBitrateLimitKbps,
      @Min(1) @Max(60) Integer remoteDesktopControlFrameRateLimitFps,
      @Min(250) @Max(100000) Integer remoteDesktopViewerBitrateLimitKbps,
      @Min(1) @Max(60) Integer remoteDesktopViewerFrameRateLimitFps) {

    public WorkspaceSettingsRequest(
        String workspaceName,
        String defaultRuntimeBuildId,
        String defaultRegion,
        boolean defaultHumanTakeoverEnabled) {
      this(
          workspaceName,
          defaultRuntimeBuildId,
          defaultRegion,
          defaultHumanTakeoverEnabled,
          null,
          null,
          null,
          null);
    }
  }

  public record WorkspaceSettingsView(
      String workspaceName,
      String defaultRuntimeBuildId,
      String defaultRegion,
      boolean defaultHumanTakeoverEnabled,
      int remoteDesktopControlBitrateLimitKbps,
      int remoteDesktopControlFrameRateLimitFps,
      int remoteDesktopViewerBitrateLimitKbps,
      int remoteDesktopViewerFrameRateLimitFps,
      String resourcePolicyMode,
      String onMaximumReached,
      String source,
      String updatedBy,
      Instant updatedAt,
      long version) {}
}
