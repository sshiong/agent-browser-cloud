import { describe, expect, it, vi } from 'vitest';
import {
  BrowserCloudClient,
  BrowserCloudError,
  BrowserCloudGeneratedClient,
} from '../src/index.js';

describe('BrowserCloudClient', () => {
  it('sends idempotent tenant-scoped create requests', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ sessionId: 'ses_1234567890abcdef' }), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    const client = new BrowserCloudClient({
      baseUrl: 'https://browsercloud.example',
      tenantId: 'tenant-a',
      actorId: 'operator-a',
      fetch: fetchMock,
    });

    await client.createSession({
      profileId: 'profile-a',
      region: 'local',
      idempotencyKey: 'idem-test',
    });

    const request = fetchMock.mock.calls[0]?.[1] as RequestInit;
    const body = JSON.parse(String(request.body)) as Record<string, unknown>;
    expect(body.resourcePolicy).toEqual({ mode: 'AUTO' });
    expect(body).not.toHaveProperty('resourceClass');
    expect(fetchMock).toHaveBeenCalledWith(
      'https://browsercloud.example/api/v1/sessions',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-a',
          'X-Actor-Id': 'operator-a',
          'Idempotency-Key': 'idem-test',
        }),
      })
    );
  });

  it('preserves structured API errors', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          code: 'CAPACITY_UNAVAILABLE',
          message: 'closed',
          requestId: 'req-1',
        }),
        { status: 503, headers: { 'Content-Type': 'application/json' } }
      )
    );
    const client = new BrowserCloudClient({
      baseUrl: 'https://browsercloud.example',
      tenantId: 'tenant-a',
      fetch: fetchMock,
    });

    await expect(client.startSession('ses_1234567890abcdef')).rejects.toEqual(
      expect.objectContaining<Partial<BrowserCloudError>>({
        status: 503,
        code: 'CAPACITY_UNAVAILABLE',
        requestId: 'req-1',
      })
    );
  });

  it('exposes all generated services through an isolated typed client', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ items: [], limit: 20, offset: 0, total: 0 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    const client = new BrowserCloudGeneratedClient({
      BASE: 'https://browsercloud.example',
      TOKEN: 'test-token',
      FETCH: fetchMock,
    });

    await client.session.listSessions({ xTenantId: 'tenant-a' });

    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = request.headers as Headers;
    expect(url).toBe(
      'https://browsercloud.example/api/v1/sessions?tagMatch=ANY&limit=20'
    );
    expect(request.method).toBe('GET');
    expect(headers.get('Authorization')).toBe('Bearer test-token');
    expect(headers.get('X-Tenant-Id')).toBe('tenant-a');
  });
});
