import { afterEach, describe, expect, it, vi } from 'vitest';
import { listSessions } from './session';

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

    await listSessions({ tenantId: 'tenant-test', limit: 10 });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions?limit=10',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
        }),
      })
    );
  });
});
