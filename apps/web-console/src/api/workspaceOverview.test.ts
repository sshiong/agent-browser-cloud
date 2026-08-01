import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  getWorkspaceOverview,
  streamWorkspaceOverviewChanges,
} from './workspaceOverview';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Workspace Overview API', () => {
  it('loads the authenticated PostgreSQL-backed aggregate', async () => {
    const overview = {
      sessions: {
        total: 8,
        running: 3,
        pending: 1,
        unhealthy: 0,
        hibernated: 1,
        terminated: 3,
      },
      operations: { active: 2 },
      browserNodes: {
        visible: true,
        total: 2,
        ready: 2,
        constrained: 0,
        activeSessions: 3,
        maximumSessions: 20,
        reservedCpuMillis: 2000,
        certifiedCpuMillis: 8000,
        reservedMemoryMib: 4096,
        certifiedMemoryMib: 16384,
      },
      proxies: { activeAllocations: 2, boundSessions: 2 },
      agents: {
        active: 1,
        awaitingHuman: 0,
        pausedByResourcePolicy: 0,
        failedLast24Hours: 0,
      },
      cost: { currentHourlyUsd: 0.25, activeSessionsWithoutCurrentPrice: 0 },
      security: { warningLast24Hours: 0, criticalLast24Hours: 0 },
      cursor: 7,
      generatedAt: '2026-08-01T00:00:00Z',
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(overview), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(getWorkspaceOverview()).resolves.toEqual(overview);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/workspace-overview',
      expect.objectContaining({
        headers: expect.objectContaining({
          Accept: 'application/json',
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
  });

  it('resumes the payload-free SSE invalidation stream', async () => {
    const encoder = new TextEncoder();
    const payload =
      'id: 7\nevent: workspace-overview-stream-ready\ndata: {"cursor":7,"resetRequired":false,"connectedAt":"2026-08-01T00:00:00Z"}\n\n' +
      'id: 9\nevent: workspace-overview-change\ndata: {"sequence":9,"changeType":"BROWSER_NODE","occurredAt":"2026-08-01T00:00:01Z","replayed":true}\n\n';
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(payload.slice(0, 47)));
        controller.enqueue(encoder.encode(payload.slice(47)));
        controller.close();
      },
    });
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(body, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      })
    );
    vi.stubGlobal('fetch', fetchMock);
    const controls: number[] = [];
    const changes: string[] = [];

    await streamWorkspaceOverviewChanges({
      lastEventId: '6',
      signal: new AbortController().signal,
      onOpen: vi.fn(),
      onControl: (control) => controls.push(control.cursor),
      onChange: (change) =>
        changes.push(`${change.sequence}:${change.changeType}`),
    });

    expect(controls).toEqual([7]);
    expect(changes).toEqual(['9:BROWSER_NODE']);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/workspace-overview/event-stream',
      expect.objectContaining({
        headers: expect.objectContaining({
          Accept: 'text/event-stream',
          'Last-Event-ID': '6',
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
  });
});
