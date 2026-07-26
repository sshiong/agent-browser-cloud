import { afterEach, describe, expect, it, vi } from 'vitest';
import { listAuditEvents, listRuntimeBuilds } from './platform';

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
});
