import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  listWorkspaceNotifications,
  streamWorkspaceNotificationChanges,
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

  it('resumes the payload-free notification SSE stream', async () => {
    const encoder = new TextEncoder();
    const payload =
      'id: 48\nevent: notification-stream-ready\ndata: {"cursor":48,"resetRequired":false,"connectedAt":"2026-08-08T00:00:00Z"}\n\n' +
      'id: 52\nevent: notification-change\ndata: {"sequence":52,"occurredAt":"2026-08-08T00:00:01Z","replayed":true}\n\n';
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(payload.slice(0, 41)));
        controller.enqueue(encoder.encode(payload.slice(41)));
        controller.close();
      },
    });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(body, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      })
    );
    const controls: number[] = [];
    const changes: string[] = [];

    await streamWorkspaceNotificationChanges({
      lastEventId: '47',
      signal: new AbortController().signal,
      onOpen: vi.fn(),
      onControl: (control) => controls.push(control.cursor),
      onChange: (change) =>
        changes.push(`${change.sequence}:${change.replayed}`),
    });

    expect(controls).toEqual([48]);
    expect(changes).toEqual(['52:true']);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/notifications/event-stream',
      expect.objectContaining({
        headers: expect.objectContaining({
          Accept: 'text/event-stream',
          'Last-Event-ID': '47',
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
  });
});
