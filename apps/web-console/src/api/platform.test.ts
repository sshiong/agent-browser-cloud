import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  createBreakGlassRequest,
  createKeyRotationRequest,
  endSecureDebugSession,
  listAuditEvents,
  listRuntimeBuilds,
  readSecureDebugSnapshot,
  startSecureDebugSession,
  streamAuditEventChanges,
  transitionBreakGlassRequest,
} from './platform';

function auditStreamResponse(payload: string, splitAt: number) {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(encoder.encode(payload.slice(0, splitAt)));
      controller.enqueue(encoder.encode(payload.slice(splitAt)));
      controller.close();
    },
  });
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  });
}

describe('platform API', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('requests security-admin audit evidence in local development', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          items: [],
          total: 0,
          chainValid: true,
          headHash: null,
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await listAuditEvents();

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/audit-events?limit=200',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-local',
          'X-Roles': 'SECURITY_ADMIN',
        }),
      })
    );
  });

  it('loads the runtime registry through the authenticated platform client', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ items: [], total: 0 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    vi.stubGlobal('fetch', fetchMock);

    await listRuntimeBuilds();

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/runtime-builds',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
  });

  it('creates Security Admin break-glass requests without leaking identity into the body', async () => {
    const responseBody = {
      requestId: 'bgr_1234567890abcdefghij',
      state: 'REQUESTED',
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(responseBody), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      ticketId: 'INC-2026-001',
      reason: 'Investigate a production incident safely',
      resourceType: 'TENANT' as const,
      resourceId: 'tenant-local',
      requestedScope: 'INCIDENT_RESPONSE' as const,
      durationMinutes: 30,
    };

    await createBreakGlassRequest(input);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/break-glass-requests',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(input),
        headers: expect.objectContaining({
          'X-Roles': 'SECURITY_ADMIN',
          'Content-Type': 'application/json',
        }),
      })
    );
  });

  it('uses explicit transition endpoints for dual approval', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          requestId: 'bgr_1234567890abcdefghij',
          state: 'ACTIVE',
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await transitionBreakGlassRequest('bgr_1234567890abcdefghij', 'approve');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/break-glass-requests/bgr_1234567890abcdefghij:approve',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-Roles': 'SECURITY_ADMIN' }),
      })
    );
  });

  it('uses the Platform Admin boundary for key rotation requests', async () => {
    const responseBody = {
      rotationId: 'rot_1234567890abcdefghij',
      state: 'REQUESTED',
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(responseBody), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      keyScope: 'NODE_MTLS' as const,
      oldKeyId: 'node-ca-v1',
      newKeyId: 'node-ca-v2',
      rotationTrigger: 'SCHEDULED' as const,
      reason: 'Rotate the node certificate authority before expiry',
      overlapMinutes: 30,
    };

    await createKeyRotationRequest(input);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/key-rotation-requests',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(input),
        headers: expect.objectContaining({
          'X-Roles': 'PLATFORM_ADMIN',
          'Content-Type': 'application/json',
        }),
      })
    );
  });

  it('uses the Security Admin boundary for the Secure Debug data plane', async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(
          JSON.stringify({
            debugSessionId: 'dbg_1234567890abcdefghij',
            state: 'ACTIVE',
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }
        )
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await startSecureDebugSession('bgr_1234567890abcdefghij');
    await readSecureDebugSnapshot('dbg_1234567890abcdefghij');
    await endSecureDebugSession('dbg_1234567890abcdefghij');

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/break-glass-requests/bgr_1234567890abcdefghij:start-secure-debug',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-Roles': 'SECURITY_ADMIN' }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/secure-debug-sessions/dbg_1234567890abcdefghij/snapshot',
      expect.objectContaining({
        method: 'GET',
        headers: expect.objectContaining({ 'X-Roles': 'SECURITY_ADMIN' }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/v1/secure-debug-sessions/dbg_1234567890abcdefghij:end',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-Roles': 'SECURITY_ADMIN' }),
      })
    );
  });
});

describe('audit event stream', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('resumes the payload-free audit SSE stream with the security admin context', async () => {
    const payload =
      'id: 48\nevent: audit-stream-ready\ndata: {"cursor":48,"resetRequired":false,"connectedAt":"2026-08-13T00:00:00Z"}\n\n' +
      'id: 52\nevent: audit-change\ndata: {"sequence":52,"occurredAt":"2026-08-13T00:00:01Z","replayed":true}\n\n';
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(auditStreamResponse(payload, 37));
    const controls: number[] = [];
    const changes: string[] = [];

    await streamAuditEventChanges({
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
      '/api/v1/audit-events/event-stream',
      expect.objectContaining({
        headers: expect.objectContaining({
          Accept: 'text/event-stream',
          'Last-Event-ID': '47',
          'X-Tenant-Id': 'tenant-local',
          'X-Roles': 'SECURITY_ADMIN',
        }),
      })
    );
  });

  it('rejects a cursor that does not advance so a stale list is never trusted', async () => {
    const payload =
      'id: 60\nevent: audit-stream-ready\ndata: {"cursor":60,"resetRequired":false,"connectedAt":"2026-08-13T00:00:00Z"}\n\n' +
      'id: 60\nevent: audit-change\ndata: {"sequence":60,"occurredAt":"2026-08-13T00:00:01Z","replayed":true}\n\n';
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      auditStreamResponse(payload, 40)
    );

    await expect(
      streamAuditEventChanges({
        signal: new AbortController().signal,
        onOpen: vi.fn(),
        onControl: vi.fn(),
        onChange: vi.fn(),
      })
    ).rejects.toThrow('Audit event stream sequence did not advance');
  });

  it('rejects a change frame whose id does not match its sequence', async () => {
    const payload =
      'id: 10\nevent: audit-stream-ready\ndata: {"cursor":10,"resetRequired":false,"connectedAt":"2026-08-13T00:00:00Z"}\n\n' +
      'id: 11\nevent: audit-change\ndata: {"sequence":12,"occurredAt":"2026-08-13T00:00:01Z","replayed":false}\n\n';
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      auditStreamResponse(payload, 40)
    );

    await expect(
      streamAuditEventChanges({
        signal: new AbortController().signal,
        onOpen: vi.fn(),
        onControl: vi.fn(),
        onChange: vi.fn(),
      })
    ).rejects.toThrow(/Audit event stream/);
  });
});
