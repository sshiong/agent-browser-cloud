import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  createSession,
  getBrowserState,
  listSessions,
  requestHumanTakeover,
  resyncBrowserState,
  SessionApiError,
  startSession,
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
});
