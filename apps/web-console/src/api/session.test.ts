import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  acquireSessionSafetyLease,
  createSession,
  getBrowserState,
  getBusinessRecovery,
  getSessionApplicationBinding,
  getSessionSafePoint,
  getSessionEvidence,
  listRecoveryContracts,
  listSessions,
  requestHumanTakeover,
  requestRecoveryContractApproval,
  decideRecoveryContractApproval,
  releaseSessionSafetyLease,
  rebindSessionApplication,
  resyncBrowserState,
  SessionApiError,
  startSession,
  streamSessionResourceChanges,
  upsertRecoveryContract,
  validateBusinessRecovery,
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

  it('reads the tenant-scoped screenshot evidence index', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ items: [], limit: 20, offset: 0 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    vi.stubGlobal('fetch', fetchMock);

    await getSessionEvidence('ses_1234567890abcdef', 'tenant-test', undefined);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions/ses_1234567890abcdef/evidence?limit=20',
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
        resourcePolicy: { mode: 'AUTO' },
        agentPolicy: 'RESTRICTED',
        extensionIds: ['automation.extension'],
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
          resourcePolicy: { mode: 'AUTO' },
          agentPolicy: 'RESTRICTED',
          extensionIds: ['automation.extension'],
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

  it('reads the tenant-scoped persistent safe-point assessment', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          sessionId: 'ses_1234567890abcdef',
          safe: false,
          state: 'UNKNOWN',
          dataFreshness: 'MISSING',
          contextEpoch: 7,
          evaluatedAt: new Date().toISOString(),
          blockers: [],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await getSessionSafePoint('ses_1234567890abcdef', 'tenant-test');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions/ses_1234567890abcdef/safe-point',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
        }),
      })
    );
  });

  it('loads recovery contracts and validates Business Recovery idempotently', async () => {
    const validation = {
      validationId: 'brv_1234567890abcdefghij',
      sessionId: 'ses_1234567890abcdef',
      applicationId: 'crm',
      contractVersion: 2,
      contextEpoch: 7,
      stateVersion: 12,
      verdict: 'READY',
      ready: true,
      evidence: ['APPLICATION_CONTRACT_SATISFIED'],
      source: 'API',
      requestId: 'request-1',
      evaluatedAt: new Date().toISOString(),
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ items: [], total: 0 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(validation), {
          status: 202,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(validation), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      );
    vi.stubGlobal('fetch', fetchMock);

    await listRecoveryContracts('tenant-test');
    await validateBusinessRecovery(
      'ses_1234567890abcdef',
      'business-recovery-1',
      'tenant-test'
    );
    await getBusinessRecovery('ses_1234567890abcdef', 'tenant-test');

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/sessions/ses_1234567890abcdef/business-recovery:validate',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'Idempotency-Key': 'business-recovery-1',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/v1/sessions/ses_1234567890abcdef/business-recovery',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
        }),
      })
    );
  });

  it('treats a missing Business Recovery verdict as not yet validated', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    );

    await expect(
      getBusinessRecovery('ses_1234567890abcdef', 'tenant-test')
    ).resolves.toBeNull();
  });

  it('treats a Session without an application binding as unbound', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    );

    await expect(
      getSessionApplicationBinding('ses_1234567890abcdef', 'tenant-test')
    ).resolves.toBeNull();
  });

  it('reads and explicitly rebinds the immutable application contract version', async () => {
    const binding = {
      sessionId: 'ses_1234567890abcdef',
      applicationId: 'crm.singapore',
      contractId: 'arc_1234567890abcdefghij',
      contractVersion: 2,
      latestContractVersion: 3,
      latestApprovalState: 'APPROVED',
      currentContractEnabled: true,
      upgradeAvailable: true,
      boundAt: new Date().toISOString(),
    };
    const operation = {
      operationId: 'op_1234567890abcdefghij',
      ...binding,
      previousContractVersion: 2,
      targetContractVersion: 3,
      state: 'COMMITTED',
      requestId: 'request-1',
      createdAt: new Date().toISOString(),
      completedAt: new Date().toISOString(),
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(binding), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(operation), {
          status: 202,
          headers: { 'Content-Type': 'application/json' },
        })
      );
    vi.stubGlobal('fetch', fetchMock);

    await getSessionApplicationBinding(
      'ses_1234567890abcdef',
      'tenant-test'
    );
    await rebindSessionApplication(
      'ses_1234567890abcdef',
      { expectedCurrentVersion: 2, targetContractVersion: 3 },
      'application-rebind-1',
      'tenant-test'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/sessions/ses_1234567890abcdef/application-binding:rebind',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          expectedCurrentVersion: 2,
          targetContractVersion: 3,
        }),
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'Idempotency-Key': 'application-rebind-1',
        }),
      })
    );
  });

  it('publishes a versioned recovery contract through the tenant API', async () => {
    const contract = {
      contractId: 'arc_1234567890abcdefghij',
      applicationId: 'crm.singapore',
      version: 3,
      expectedOrigins: ['https://crm.example.test'],
      readyRoutePrefixes: ['/workspace'],
      loginRoutePrefixes: ['/sign-in'],
      requiredTargets: [{ role: 'status', name: 'Recovered workspace' }],
      loginTargets: [],
      permissionDeniedTargets: [],
      accountMismatchTargets: [],
      requiredExtensionIds: [],
      allowDepthLimited: false,
      recoveryAction: 'RELOAD',
      maximumAutoRecovery: 1,
      enabled: true,
      approvalState: 'DRAFT',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(contract), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    vi.stubGlobal('fetch', fetchMock);

    await upsertRecoveryContract(
      'crm.singapore',
      {
        expectedVersion: 2,
        expectedOrigins: ['https://crm.example.test'],
        readyRoutePrefixes: ['/workspace'],
        loginRoutePrefixes: ['/sign-in'],
        requiredTargets: [{ role: 'status', name: 'Recovered workspace' }],
        loginTargets: [],
        permissionDeniedTargets: [],
        accountMismatchTargets: [],
        requiredExtensionIds: [],
        allowDepthLimited: false,
        recoveryAction: 'RELOAD',
        maximumAutoRecovery: 1,
        enabled: true,
      },
      'tenant-test'
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/applications/crm.singapore/recovery-contract',
      expect.objectContaining({
        method: 'PUT',
        body: expect.stringContaining('"expectedVersion":2'),
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
        }),
      })
    );
  });

  it('requests and decides an exact-version recovery contract approval', async () => {
    const approval = {
      approvalId: 'ara_1234567890abcdefghij',
      contractId: 'arc_1234567890abcdefghij',
      applicationId: 'crm.singapore',
      contractVersion: 3,
      reason: 'Production gate',
      state: 'REQUESTED',
      requestedBy: 'admin-a',
      requestedAt: new Date().toISOString(),
    };
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify(approval), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await requestRecoveryContractApproval(
      'crm.singapore',
      { expectedVersion: 3, reason: 'Production gate' },
      'tenant-test'
    );
    await decideRecoveryContractApproval(
      'crm.singapore',
      approval.approvalId,
      'approve',
      'tenant-test'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/applications/crm.singapore/recovery-contract:request-approval',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          expectedVersion: 3,
          reason: 'Production gate',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/applications/crm.singapore/recovery-contract-approvals/ara_1234567890abcdefghij:approve',
      expect.objectContaining({ method: 'POST' })
    );
  });

  it('acquires and releases an owner-bound safety lease with idempotency', async () => {
    const lease = {
      leaseId: 'sfl_1234567890abcdef',
      sessionId: 'ses_1234567890abcdef',
      contextEpoch: 7,
      signalType: 'PAYMENT_OR_SECURITY',
      reasonCode: 'CHECKOUT_COMMIT',
      ownerActorId: 'app-adapter',
      state: 'ACTIVE',
      acquiredAt: new Date().toISOString(),
      renewedAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + 30_000).toISOString(),
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(lease), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ ...lease, state: 'RELEASED' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      );
    vi.stubGlobal('fetch', fetchMock);

    await acquireSessionSafetyLease(
      'ses_1234567890abcdef',
      {
        signalType: 'PAYMENT_OR_SECURITY',
        reasonCode: 'CHECKOUT_COMMIT',
        ttlSeconds: 30,
      },
      'idem-lease-acquire',
      'tenant-test'
    );
    await releaseSessionSafetyLease(
      'ses_1234567890abcdef',
      'sfl_1234567890abcdef',
      'idem-lease-release',
      'tenant-test'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/sessions/ses_1234567890abcdef/safety-leases',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'Idempotency-Key': 'idem-lease-acquire',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/sessions/ses_1234567890abcdef/safety-leases/sfl_1234567890abcdef:release',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'idem-lease-release',
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

  it('consumes resumable authenticated resource SSE across chunk boundaries', async () => {
    const encoder = new TextEncoder();
    const payload =
      'id: 4\r\nevent: resource-stream-ready\r\ndata: {"cursor":4,"resetRequired":false,"connectedAt":"2026-07-28T00:00:00Z"}\r\n\r\n' +
      'id: 5\r\nevent: session-resource-change\r\ndata: {"sequence":5,"changeType":"RESOURCE_SAMPLE","entityId":"rs_5","occurredAt":"2026-07-28T00:00:05Z","replayed":true}\r\n\r\n' +
      'id: 6\r\nevent: session-resource-change\r\ndata: {"sequence":6,"changeType":"SAFETY_LEASE_EVENT","entityId":"sle_6","occurredAt":"2026-07-28T00:00:06Z","replayed":false}\r\n\r\n';
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(payload.slice(0, 37)));
        controller.enqueue(encoder.encode(payload.slice(37, 121)));
        controller.enqueue(encoder.encode(payload.slice(121)));
        controller.close();
      },
    });
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(body, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      })
    );
    vi.stubGlobal('fetch', fetchMock);
    const controls: number[] = [];
    const changes: string[] = [];

    await streamSessionResourceChanges({
      sessionId: 'ses_1234567890abcdef',
      tenantId: 'tenant-test',
      lastEventId: '3',
      signal: new AbortController().signal,
      onOpen: vi.fn(),
      onControl: (control) => controls.push(control.cursor),
      onChange: (change) =>
        changes.push(`${change.sequence}:${change.changeType}`),
    });

    expect(controls).toEqual([4]);
    expect(changes).toEqual(['5:RESOURCE_SAMPLE', '6:SAFETY_LEASE_EVENT']);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions/ses_1234567890abcdef/resource-stream',
      expect.objectContaining({
        headers: expect.objectContaining({
          Accept: 'text/event-stream',
          'Last-Event-ID': '3',
          'X-Tenant-Id': 'tenant-test',
        }),
      })
    );
  });

  it('rejects a resource SSE event whose frame ID disagrees with the payload', async () => {
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            'id: 8\n' +
              'event: resource-stream-ready\n' +
              'data: {"cursor":7,"resetRequired":false,"connectedAt":"2026-07-28T00:00:00Z"}\n\n'
          )
        );
        controller.close();
      },
    });
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(body, { status: 200 }))
    );

    await expect(
      streamSessionResourceChanges({
        sessionId: 'ses_1234567890abcdef',
        signal: new AbortController().signal,
        onOpen: vi.fn(),
        onControl: vi.fn(),
        onChange: vi.fn(),
      })
    ).rejects.toThrow('event ID does not match');
  });
});
