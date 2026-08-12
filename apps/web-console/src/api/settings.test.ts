import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getWorkspaceSettings, updateWorkspaceSettings } from './settings';
import { setRuntimeIdentity } from '@/auth/runtimeIdentity';

describe('workspace settings api', () => {
  beforeEach(() => {
    setRuntimeIdentity({
      tenantId: 'tenant-settings',
      actorId: 'admin-settings',
      roles: ['TENANT_ADMIN'],
      accessToken: 'settings-token',
    });
  });

  it('uses the authenticated identity and an idempotency key', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(async () => {
        return new Response(
          JSON.stringify({
            workspaceName: 'Operations',
            defaultRuntimeBuildId: 'runtime_local_chromium',
            defaultRegion: 'local',
            defaultHumanTakeoverEnabled: true,
            remoteDesktopControlBitrateLimitKbps: 8000,
            remoteDesktopControlFrameRateLimitFps: 30,
            remoteDesktopViewerBitrateLimitKbps: 4000,
            remoteDesktopViewerFrameRateLimitFps: 15,
            resourcePolicyMode: 'AUTO',
            onMaximumReached: 'PAUSE_AGENT',
            source: 'WORKSPACE_OVERRIDE',
            updatedBy: 'admin-settings',
            updatedAt: '2026-07-28T00:00:00Z',
            version: 1,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      });

    await getWorkspaceSettings();
    await updateWorkspaceSettings(
      {
        workspaceName: 'Operations',
        defaultRuntimeBuildId: 'runtime_local_chromium',
        defaultRegion: 'local',
        defaultHumanTakeoverEnabled: false,
        remoteDesktopControlBitrateLimitKbps: 10000,
        remoteDesktopControlFrameRateLimitFps: 40,
        remoteDesktopViewerBitrateLimitKbps: 3000,
        remoteDesktopViewerFrameRateLimitFps: 12,
      },
      'settings-update-1'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/workspace-settings',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer settings-token',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/workspace-settings',
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({
          'Idempotency-Key': 'settings-update-1',
        }),
      })
    );
  });
});
