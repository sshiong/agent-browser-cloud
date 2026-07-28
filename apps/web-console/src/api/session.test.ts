import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  acquireSessionSafetyLease,
  createSession,
  getBrowserState,
  getBusinessRecovery,
  getSessionSafePoint,
  listRecoveryContracts,
  listSessions,
  requestHumanTakeover,
  releaseSessionSafetyLease,
  resyncBrowserState,
  SessionApiError,
  startSession,
  streamSessionResourceChanges,
  validateBusinessRecovery,
} from './session';

describe('session API', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('scopes list requests with the tenant header', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ items: [], total: 0 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    vi.stubGlobal('fetch', fetchMock);

    await listSessions({
      tenantId: 'tenant-test',
      query: '  crm singapore  ',
      limit: 10,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions?q=crm+singapore&limit=10',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
        }),
      })
    );
  });

  it('serializes create requests with an idempotency key', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          sessionId: 'ses_1234567890abcdef',
          context: { sessionId: 'ses_1234567890abcdef' },
        }),
        {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await createSession(
      {
        tenantId: 'tenant-test',
        profileId: 'profile-test',
        region: 'local',
        resourceClass: 'L2',
        agentPolicy: 'RESTRICTED',
        extensionIds: ['automation.extension'],
      },
      'idem-test'
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          tenantId: 'tenant-test',
          profileId: 'profile-test',
          region: 'local',
          resourceClass: 'L2',
          agentPolicy: 'RESTRICTED',
          extensionIds: ['automation.extension'],
        }),
        headers: expect.objectContaining({
          'Idempotency-Key': 'idem-test',
        }),
      })
    );
  });

  it('scopes start operations with the tenant header', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ operationId: 'op_test', state: 'ACTIVE' }),
        {
          status: 202,
          headers: { 'Content-Type': 'application/json' },
        }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await startSession('ses_1234567890abcdef', 'tenant-test');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions/ses_1234567890abcdef:start',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
        }),
      })
    );
  });

  it('treats an uncollected browser state as an empty result', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      getBrowserState('ses_1234567890abcdef', 'tenant-test')
    ).resolves.toBeNull();
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions/ses_1234567890abcdef/state',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
        }),
      })
    );
  });

  it('reads the tenant-scoped persistent safe-point assessment', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          sessionId: 'ses_1234567890abcdef',
          safe: false,
          state: 'UNKNOWN',
          dataFreshness: 'MISSING',
          contextEpoch: 7,
          evaluatedAt: new Date().toISOString(),
          blockers: [],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await getSessionSafePoint('ses_1234567890abcdef', 'tenant-test');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions/ses_1234567890abcdef/safe-point',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
        }),
      })
    );
  });

  it('loads recovery contracts and validates Business Recovery idempotently', async () => {
    const validation = {
      validationId: 'brv_1234567890abcdefghij',
      sessionId: 'ses_1234567890abcdef',
      applicationId: 'crm',
      contractVersion: 2,
      contextEpoch: 7,
      stateVersion: 12,
      verdict: 'READY',
      ready: true,
      evidence: ['APPLICATION_CONTRACT_SATISFIED'],
      source: 'API',
      requestId: 'request-1',
      evaluatedAt: new Date().toISOString(),
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ items: [], total: 0 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(validation), {
          status: 202,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(validation), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      );
    vi.stubGlobal('fetch', fetchMock);

    await listRecoveryContracts('tenant-test');
    await validateBusinessRecovery(
      'ses_1234567890abcdef',
      'business-recovery-1',
      'tenant-test'
    );
    await getBusinessRecovery('ses_1234567890abcdef', 'tenant-test');

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/sessions/ses_1234567890abcdef/business-recovery:validate',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'Idempotency-Key': 'business-recovery-1',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/v1/sessions/ses_1234567890abcdef/business-recovery',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
        }),
      })
    );
  });

  it('treats a missing Business Recovery verdict as not yet validated', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(null, { status: 404 }))
    );

    await expect(
      getBusinessRecovery('ses_1234567890abcdef', 'tenant-test')
    ).resolves.toBeNull();
  });

  it('acquires and releases an owner-bound safety lease with idempotency', async () => {
    const lease = {
      leaseId: 'sfl_1234567890abcdef',
      sessionId: 'ses_1234567890abcdef',
      contextEpoch: 7,
      signalType: 'PAYMENT_OR_SECURITY',
      reasonCode: 'CHECKOUT_COMMIT',
      ownerActorId: 'app-adapter',
      state: 'ACTIVE',
      acquiredAt: new Date().toISOString(),
      renewedAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + 30_000).toISOString(),
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(lease), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ ...lease, state: 'RELEASED' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      );
    vi.stubGlobal('fetch', fetchMock);

    await acquireSessionSafetyLease(
      'ses_1234567890abcdef',
      {
        signalType: 'PAYMENT_OR_SECURITY',
        reasonCode: 'CHECKOUT_COMMIT',
        ttlSeconds: 30,
      },
      'idem-lease-acquire',
      'tenant-test'
    );
    await releaseSessionSafetyLease(
      'ses_1234567890abcdef',
      'sfl_1234567890abcdef',
      'idem-lease-release',
      'tenant-test'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/sessions/ses_1234567890abcdef/safety-leases',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'Idempotency-Key': 'idem-lease-acquire',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/sessions/ses_1234567890abcdef/safety-leases/sfl_1234567890abcdef:release',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'idem-lease-release',
        }),
      })
    );
  });

  it('binds HumanTakeover to tenant and actor headers', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ operationId: 'op_takeover', state: 'ACTIVE' }),
        {
          status: 202,
          headers: { 'Content-Type': 'application/json' },
        }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await requestHumanTakeover(
      'ses_1234567890abcdef',
      'tenant-test',
      'user-test'
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions/ses_1234567890abcdef:takeover',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'X-Actor-Id': 'user-test',
        }),
      })
    );
  });

  it('preserves the structured backend error and request id', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          code: 'SESSION_NOT_FOUND',
          message: 'Session not found',
          requestId: 'req-test',
        }),
        {
          status: 404,
          headers: { 'Content-Type': 'application/json' },
        }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(listSessions({ tenantId: 'tenant-test' })).rejects.toEqual(
      expect.objectContaining<Partial<SessionApiError>>({
        status: 404,
        body: expect.objectContaining({ requestId: 'req-test' }),
      })
    );
  });

  it('requests tenant-scoped Full State Resync with an idempotency key', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          requestId: 'cmd_state_1',
          mode: 'FULL',
          state: 'QUEUED',
        }),
        { status: 202, headers: { 'Content-Type': 'application/json' } }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await resyncBrowserState(
      'ses_1234567890abcdef',
      { mode: 'FULL', reason: 'TEST' },
      'idem-state-1',
      'tenant-test'
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions/ses_1234567890abcdef:resync-state',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'Idempotency-Key': 'idem-state-1',
        }),
      })
    );
  });

  it('consumes resumable authenticated resource SSE across chunk boundaries', async () => {
    const encoder = new TextEncoder();
    const payload =
      'id: 4\r\nevent: resource-stream-ready\r\ndata: {"cursor":4,"resetRequired":false,"connectedAt":"2026-07-28T00:00:00Z"}\r\n\r\n' +
      'id: 5\r\nevent: session-resource-change\r\ndata: {"sequence":5,"changeType":"RESOURCE_SAMPLE","entityId":"rs_5","occurredAt":"2026-07-28T00:00:05Z","replayed":true}\r\n\r\n' +
      'id: 6\r\nevent: session-resource-change\r\ndata: {"sequence":6,"changeType":"SAFETY_LEASE_EVENT","entityId":"sle_6","occurredAt":"2026-07-28T00:00:06Z","replayed":false}\r\n\r\n';
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(payload.slice(0, 37)));
        controller.enqueue(encoder.encode(payload.slice(37, 121)));
        controller.enqueue(encoder.encode(payload.slice(121)));
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

    await streamSessionResourceChanges({
      sessionId: 'ses_1234567890abcdef',
      tenantId: 'tenant-test',
      lastEventId: '3',
      signal: new AbortController().signal,
      onOpen: vi.fn(),
      onControl: (control) => controls.push(control.cursor),
      onChange: (change) =>
        changes.push(`${change.sequence}:${change.changeType}`),
    });

    expect(controls).toEqual([4]);
    expect(changes).toEqual(['5:RESOURCE_SAMPLE', '6:SAFETY_LEASE_EVENT']);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions/ses_1234567890abcdef/resource-stream',
      expect.objectContaining({
        headers: expect.objectContaining({
          Accept: 'text/event-stream',
          'Last-Event-ID': '3',
          'X-Tenant-Id': 'tenant-test',
        }),
      })
    );
  });

  it('rejects a resource SSE event whose frame ID disagrees with the payload', async () => {
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            'id: 8\n' +
              'event: resource-stream-ready\n' +
              'data: {"cursor":7,"resetRequired":false,"connectedAt":"2026-07-28T00:00:00Z"}\n\n'
          )
        );
        controller.close();
      },
    });
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(body, { status: 200 }))
    );

    await expect(
      streamSessionResourceChanges({
        sessionId: 'ses_1234567890abcdef',
        signal: new AbortController().signal,
        onOpen: vi.fn(),
        onControl: vi.fn(),
        onChange: vi.fn(),
      })
    ).rejects.toThrow('event ID does not match');
  });
});
