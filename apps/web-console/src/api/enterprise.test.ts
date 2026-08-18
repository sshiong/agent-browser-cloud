import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  getEnterpriseOverview,
  streamEnterpriseOverviewChanges,
} from './enterprise';

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
          releaseFreeze: null,
          retentionPolicies: [],
          regions: [],
          recoveryGameDays: [],
          recoveryGameDayTrends: [],
          recoveryGameDayRemediations: [],
          latestCompliance: null,
          mediaQuota: null,
          slaExclusions: [],
          licenseInventory: [],
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

  it('resumes and validates the payload-free enterprise overview stream', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        'id: 8\nevent: enterprise-overview-stream-ready\ndata: {"cursor":8,"resetRequired":false,"connectedAt":"2026-08-18T00:00:00Z"}\n\n' +
          'id: 10\nevent: enterprise-overview-change\ndata: {"sequence":10,"changeType":"MEDIA_QUOTA","occurredAt":"2026-08-18T00:00:01Z","replayed":true}\n\n',
        {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }
      )
    );
    vi.stubGlobal('fetch', fetchMock);
    const onOpen = vi.fn();
    const onControl = vi.fn();
    const onChange = vi.fn();

    await streamEnterpriseOverviewChanges({
      lastEventId: '7',
      signal: new AbortController().signal,
      onOpen,
      onControl,
      onChange,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/enterprise/overview/event-stream',
      expect.objectContaining({
        headers: expect.objectContaining({
          Accept: 'text/event-stream',
          'Last-Event-ID': '7',
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
    expect(onOpen).toHaveBeenCalledOnce();
    expect(onControl).toHaveBeenCalledWith(
      expect.objectContaining({ cursor: 8, resetRequired: false })
    );
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ sequence: 10, changeType: 'MEDIA_QUOTA' })
    );
  });
});
