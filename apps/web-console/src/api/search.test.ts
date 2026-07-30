import { afterEach, describe, expect, it, vi } from 'vitest';
import { globalSearch } from './search';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('Global Search API', () => {
  it('uses bounded query parameters and authenticated tenant context', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          query: 'CRM Singapore',
          items: [],
          limit: 12,
          truncated: false,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    );

    await globalSearch(' CRM Singapore ', ['SESSION', 'PROFILE'], 12);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/search?q=CRM+Singapore&limit=12&types=SESSION%2CPROFILE',
      expect.objectContaining({
        headers: expect.objectContaining({
          Accept: 'application/json',
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
  });
});
