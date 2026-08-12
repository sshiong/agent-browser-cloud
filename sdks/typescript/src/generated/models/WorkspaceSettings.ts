/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
export type WorkspaceSettings = {
    workspaceName: string;
    defaultRuntimeBuildId: string;
    defaultRegion: string;
    defaultHumanTakeoverEnabled: boolean;
    remoteDesktopControlBitrateLimitKbps: number;
    remoteDesktopControlFrameRateLimitFps: number;
    remoteDesktopViewerBitrateLimitKbps: number;
    remoteDesktopViewerFrameRateLimitFps: number;
    resourcePolicyMode: string;
    onMaximumReached: string;
    source: 'SYSTEM_DEFAULT' | 'WORKSPACE_OVERRIDE';
    updatedBy?: string | null;
    updatedAt?: string | null;
    version: number;
};
