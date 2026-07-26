import { afterEach, describe, expect, it, vi } from 'vitest';
import { getEnterpriseOverview } from './enterprise';

describe('enterprise operations API', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('loads tenant-scoped production operations data', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          validations: [],
          costRates: [],
          errorBudget: null,
          retentionPolicies: [],
          regions: [],
          recoveryGameDays: [],
          latestCompliance: null,
          generatedAt: new Date().toISOString(),
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await getEnterpriseOverview();

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/enterprise/overview',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
  });
});
