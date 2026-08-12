package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "workspace_settings")
public class WorkspaceSettingsEntity {

  @Id
  @Column(name = "tenant_id")
  private String tenantId;

  @Column(name = "workspace_name", nullable = false)
  private String workspaceName;

  @Column(name = "default_runtime_build_id", nullable = false)
  private String defaultRuntimeBuildId;

  @Column(name = "default_region", nullable = false)
  private String defaultRegion;

  @Column(name = "default_human_takeover_enabled", nullable = false)
  private boolean defaultHumanTakeoverEnabled;

  @Column(name = "remote_desktop_control_bitrate_limit_kbps", nullable = false)
  private int remoteDesktopControlBitrateLimitKbps;

  @Column(name = "remote_desktop_control_frame_rate_limit_fps", nullable = false)
  private int remoteDesktopControlFrameRateLimitFps;

  @Column(name = "remote_desktop_viewer_bitrate_limit_kbps", nullable = false)
  private int remoteDesktopViewerBitrateLimitKbps;

  @Column(name = "remote_desktop_viewer_frame_rate_limit_fps", nullable = false)
  private int remoteDesktopViewerFrameRateLimitFps;

  @Column(name = "updated_by", nullable = false)
  private String updatedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected WorkspaceSettingsEntity() {}

  public String getTenantId() {
    return tenantId;
  }

  public String getWorkspaceName() {
    return workspaceName;
  }

  public String getDefaultRuntimeBuildId() {
    return defaultRuntimeBuildId;
  }

  public String getDefaultRegion() {
    return defaultRegion;
  }

  public boolean isDefaultHumanTakeoverEnabled() {
    return defaultHumanTakeoverEnabled;
  }

  public int getRemoteDesktopControlBitrateLimitKbps() {
    return remoteDesktopControlBitrateLimitKbps;
  }

  public int getRemoteDesktopControlFrameRateLimitFps() {
    return remoteDesktopControlFrameRateLimitFps;
  }

  public int getRemoteDesktopViewerBitrateLimitKbps() {
    return remoteDesktopViewerBitrateLimitKbps;
  }

  public int getRemoteDesktopViewerFrameRateLimitFps() {
    return remoteDesktopViewerFrameRateLimitFps;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }
}
