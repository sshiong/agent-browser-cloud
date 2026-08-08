import { afterEach, describe, expect, it, vi } from 'vitest';
import { getProxyOverview } from './proxy';

afterEach(() => vi.restoreAllMocks());

describe('proxy API', () => {
  it('loads tenant-scoped provider and allocation observations', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          provider: {
            providerId: 'static-test',
            type: 'STATIC_HTTP',
            endpoint: 'http://127.0.0.1:8081',
            expectedExitIp: '203.0.113.10',
            directFallbackAllowed: false,
            state: 'CONFIGURED',
            regions: ['singapore'],
            costPerGibUsd: 0.125,
            reputationScore: 91,
            maxConcurrentSessions: 400,
          },
          providers: [
            {
              providerId: 'static-test',
              type: 'STATIC_HTTP',
              endpoint: 'http://127.0.0.1:8081',
              expectedExitIp: '203.0.113.10',
              directFallbackAllowed: false,
              state: 'CONFIGURED',
              regions: ['singapore'],
              costPerGibUsd: 0.125,
              reputationScore: 91,
              maxConcurrentSessions: 400,
            },
          ],
          allocations: [],
          total: 0,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    );

    const overview = await getProxyOverview('tenant-test');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/proxies',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-Tenant-Id': 'tenant-test' }),
      })
    );
    expect(overview.providers).toHaveLength(1);
    expect(overview.providers[0]?.reputationScore).toBe(91);
  });
});
