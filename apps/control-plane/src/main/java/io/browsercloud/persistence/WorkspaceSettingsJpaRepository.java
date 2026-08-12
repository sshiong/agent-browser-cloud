package io.browsercloud.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceSettingsJpaRepository
    extends JpaRepository<WorkspaceSettingsEntity, String> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO workspace_settings (
            tenant_id,
            workspace_name,
            default_runtime_build_id,
            default_region,
            default_human_takeover_enabled,
            remote_desktop_control_bitrate_limit_kbps,
            remote_desktop_control_frame_rate_limit_fps,
            remote_desktop_viewer_bitrate_limit_kbps,
            remote_desktop_viewer_frame_rate_limit_fps,
            updated_by,
            created_at,
            updated_at,
            version
          ) VALUES (
            :tenantId,
            :workspaceName,
            :defaultRuntimeBuildId,
            :defaultRegion,
            :defaultHumanTakeoverEnabled,
            :remoteDesktopControlBitrateLimitKbps,
            :remoteDesktopControlFrameRateLimitFps,
            :remoteDesktopViewerBitrateLimitKbps,
            :remoteDesktopViewerFrameRateLimitFps,
            :updatedBy,
            :updatedAt,
            :updatedAt,
            0
          )
          ON CONFLICT (tenant_id) DO UPDATE SET
            workspace_name = EXCLUDED.workspace_name,
            default_runtime_build_id = EXCLUDED.default_runtime_build_id,
            default_region = EXCLUDED.default_region,
            default_human_takeover_enabled = EXCLUDED.default_human_takeover_enabled,
            remote_desktop_control_bitrate_limit_kbps = EXCLUDED.remote_desktop_control_bitrate_limit_kbps,
            remote_desktop_control_frame_rate_limit_fps = EXCLUDED.remote_desktop_control_frame_rate_limit_fps,
            remote_desktop_viewer_bitrate_limit_kbps = EXCLUDED.remote_desktop_viewer_bitrate_limit_kbps,
            remote_desktop_viewer_frame_rate_limit_fps = EXCLUDED.remote_desktop_viewer_frame_rate_limit_fps,
            updated_by = EXCLUDED.updated_by,
            updated_at = EXCLUDED.updated_at,
            version = workspace_settings.version + 1
          """,
      nativeQuery = true)
  int upsert(
      @Param("tenantId") String tenantId,
      @Param("workspaceName") String workspaceName,
      @Param("defaultRuntimeBuildId") String defaultRuntimeBuildId,
      @Param("defaultRegion") String defaultRegion,
      @Param("defaultHumanTakeoverEnabled") boolean defaultHumanTakeoverEnabled,
      @Param("remoteDesktopControlBitrateLimitKbps") int remoteDesktopControlBitrateLimitKbps,
      @Param("remoteDesktopControlFrameRateLimitFps") int remoteDesktopControlFrameRateLimitFps,
      @Param("remoteDesktopViewerBitrateLimitKbps") int remoteDesktopViewerBitrateLimitKbps,
      @Param("remoteDesktopViewerFrameRateLimitFps") int remoteDesktopViewerFrameRateLimitFps,
      @Param("updatedBy") String updatedBy,
      @Param("updatedAt") Instant updatedAt);
}
