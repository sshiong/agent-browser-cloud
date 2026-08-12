ALTER TABLE workspace_settings
  ADD COLUMN remote_desktop_control_bitrate_limit_kbps INTEGER NOT NULL DEFAULT 8000,
  ADD COLUMN remote_desktop_control_frame_rate_limit_fps INTEGER NOT NULL DEFAULT 30,
  ADD COLUMN remote_desktop_viewer_bitrate_limit_kbps INTEGER NOT NULL DEFAULT 4000,
  ADD COLUMN remote_desktop_viewer_frame_rate_limit_fps INTEGER NOT NULL DEFAULT 15;

ALTER TABLE workspace_settings
  ADD CONSTRAINT chk_workspace_remote_desktop_control_bitrate
    CHECK (remote_desktop_control_bitrate_limit_kbps BETWEEN 250 AND 100000) NOT VALID,
  ADD CONSTRAINT chk_workspace_remote_desktop_control_fps
    CHECK (remote_desktop_control_frame_rate_limit_fps BETWEEN 1 AND 60) NOT VALID,
  ADD CONSTRAINT chk_workspace_remote_desktop_viewer_bitrate
    CHECK (remote_desktop_viewer_bitrate_limit_kbps BETWEEN 250 AND 100000) NOT VALID,
  ADD CONSTRAINT chk_workspace_remote_desktop_viewer_fps
    CHECK (remote_desktop_viewer_frame_rate_limit_fps BETWEEN 1 AND 60) NOT VALID;

ALTER TABLE workspace_settings
  VALIDATE CONSTRAINT chk_workspace_remote_desktop_control_bitrate;
ALTER TABLE workspace_settings
  VALIDATE CONSTRAINT chk_workspace_remote_desktop_control_fps;
ALTER TABLE workspace_settings
  VALIDATE CONSTRAINT chk_workspace_remote_desktop_viewer_bitrate;
ALTER TABLE workspace_settings
  VALIDATE CONSTRAINT chk_workspace_remote_desktop_viewer_fps;

COMMENT ON COLUMN workspace_settings.remote_desktop_control_bitrate_limit_kbps IS
  'Workspace-authoritative signed output ceiling shared by one controlling remote desktop actor';
COMMENT ON COLUMN workspace_settings.remote_desktop_control_frame_rate_limit_fps IS
  'Workspace-authoritative signed forwarding frequency shared by one controlling actor';
COMMENT ON COLUMN workspace_settings.remote_desktop_viewer_bitrate_limit_kbps IS
  'Workspace-authoritative signed output ceiling shared by one view-only remote desktop actor';
COMMENT ON COLUMN workspace_settings.remote_desktop_viewer_frame_rate_limit_fps IS
  'Workspace-authoritative signed forwarding frequency shared by one view-only actor';
