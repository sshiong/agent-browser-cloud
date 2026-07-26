import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  createBreakGlassRequest,
  listAuditEvents,
  listRuntimeBuilds,
  transitionBreakGlassRequest,
} from './platform';

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

  it('creates Security Admin break-glass requests without leaking identity into the body', async () => {
    const responseBody = {
      requestId: 'bgr_1234567890abcdefghij',
      state: 'REQUESTED',
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(responseBody), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    vi.stubGlobal('fetch', fetchMock);
    const input = {
      ticketId: 'INC-2026-001',
      reason: 'Investigate a production incident safely',
      resourceType: 'TENANT' as const,
      resourceId: 'tenant-local',
      requestedScope: 'INCIDENT_RESPONSE' as const,
      durationMinutes: 30,
    };

    await createBreakGlassRequest(input);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/break-glass-requests',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(input),
        headers: expect.objectContaining({
          'X-Roles': 'SECURITY_ADMIN',
          'Content-Type': 'application/json',
        }),
      })
    );
  });

  it('uses explicit transition endpoints for dual approval', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          requestId: 'bgr_1234567890abcdefghij',
          state: 'ACTIVE',
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await transitionBreakGlassRequest('bgr_1234567890abcdefghij', 'approve');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/break-glass-requests/bgr_1234567890abcdefghij:approve',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-Roles': 'SECURITY_ADMIN' }),
      })
    );
  });
});
