import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  listWorkspaceNotifications,
  updateWorkspaceNotificationReadCursor,
} from './notification';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('Workspace Notification API', () => {
  it('uses a bounded cursor and authenticated tenant context', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          items: [],
          unreadCount: 0,
          lastReadSequence: 0,
          headSequence: 0,
          nextBeforeSequence: null,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    );

    await listWorkspaceNotifications(20, 48);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/notifications?limit=20&beforeSequence=48',
      expect.objectContaining({
        headers: expect.objectContaining({
          Accept: 'application/json',
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
  });

  it('persists the monotonic actor read cursor through the API', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          lastReadSequence: 52,
          unreadCount: 0,
          updatedAt: '2026-07-31T08:00:00Z',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    );

    await updateWorkspaceNotificationReadCursor(52);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/notifications/read-cursor',
      expect.objectContaining({
        method: 'PATCH',
        body: JSON.stringify({ readThroughSequence: 52 }),
      })
    );
  });
});
