import { afterEach, describe, expect, it, vi } from 'vitest';
import { listBrowserNodes, listExtensionProfiles } from './capacity';

describe('capacity API', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('loads Browser Node and Extension authority from the Control Plane', async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify({ items: [], total: 0 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await listBrowserNodes();
    await listExtensionProfiles();

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/browser-nodes',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/extensions',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
  });
});
