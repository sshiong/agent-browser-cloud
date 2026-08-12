export interface WorkspaceSettingsView {
  workspaceName: string;
  defaultRuntimeBuildId: string;
  defaultRegion: string;
  defaultHumanTakeoverEnabled: boolean;
  remoteDesktopControlBitrateLimitKbps: number;
  remoteDesktopControlFrameRateLimitFps: number;
  remoteDesktopViewerBitrateLimitKbps: number;
  remoteDesktopViewerFrameRateLimitFps: number;
  resourcePolicyMode: 'AUTO';
  onMaximumReached: 'PAUSE_AGENT';
  source: 'SYSTEM_DEFAULT' | 'WORKSPACE_OVERRIDE';
  updatedBy: string | null;
  updatedAt: string | null;
  version: number;
}

export interface WorkspaceSettingsRequest {
  workspaceName: string;
  defaultRuntimeBuildId: string;
  defaultRegion: string;
  defaultHumanTakeoverEnabled: boolean;
  remoteDesktopControlBitrateLimitKbps?: number;
  remoteDesktopControlFrameRateLimitFps?: number;
  remoteDesktopViewerBitrateLimitKbps?: number;
  remoteDesktopViewerFrameRateLimitFps?: number;
}
