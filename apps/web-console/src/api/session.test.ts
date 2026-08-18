import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  acquireSessionSafetyLease,
  authorizeHumanAssist,
  captureSessionEvidence,
  createSessionEvidenceAccessGrant,
  createRemoteDesktopConnection,
  createSession,
  getBrowserState,
  getBusinessRecovery,
  getChallengePreview,
  getChallengeAutomationPolicy,
  getCurrentChallengeAutomationRun,
  getRecoveryContractDiff,
  getRemoteDesktopParticipants,
  getRemoteDesktopParticipantHistory,
  getSessionApplicationBinding,
  getSessionSafePoint,
  getSessionEvidence,
  getSessionRecordings,
  getSessionChallenges,
  getSessionProxyRebind,
  listRecoveryContracts,
  listRecoveryContractRevisions,
  listSessions,
  requestHumanTakeover,
  revokeRemoteDesktopParticipant,
  requestRecoveryContractApproval,
  decideRecoveryContractApproval,
  releaseSessionSafetyLease,
  redeemSessionEvidenceAccessGrant,
  restoreRecoveryContractRevision,
  rebindSessionApplication,
  rebindSessionProxy,
  resyncBrowserState,
  SessionApiError,
  startSession,
  streamSessionChanges,
  streamSessionResourceChanges,
  upsertRecoveryContract,
  validateBusinessRecovery,
  updateChallengeAutomationPolicy,
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
      groupId: 'grp_1234567890abcdef',
      tagIds: ['tag_1234567890abcdef', 'tag_fedcba0987654321'],
      tagMatch: 'ALL',
      limit: 10,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions?q=crm+singapore&groupId=grp_1234567890abcdef&tagId=tag_1234567890abcdef&tagId=tag_fedcba0987654321&tagMatch=ALL&limit=10',
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

  it('reads the tenant-scoped immutable recording manifest index', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ items: [], limit: 20, offset: 0 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    vi.stubGlobal('fetch', fetchMock);

    await getSessionRecordings(
      'ses_1234567890abcdef',
      'tenant-test',
      undefined
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions/ses_1234567890abcdef/recordings?limit=20',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
        }),
      })
    );
  });

  it('requests a server-enforced view-only remote desktop ticket', async () => {
    const connection = {
      connectionId: 'rdc_1234567890abcdefghij',
      webSocketPath: '/desktop/v1/sessions/ses_1234567890abcdef?ticket=opaque',
      expiresAt: new Date(Date.now() + 45_000).toISOString(),
      protocol: 'rfb',
      operationEpoch: 3,
      viewOnly: true,
      actorBitrateLimitKbps: 4000,
      actorFrameRateLimitFps: 15,
    };
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(connection), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    vi.stubGlobal('fetch', fetchMock);

    await createRemoteDesktopConnection(
      'ses_1234567890abcdef',
      'tenant-test',
      'viewer-test',
      undefined,
      true
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions/ses_1234567890abcdef:desktop-connection?viewOnly=true',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'X-Actor-Id': 'viewer-test',
        }),
      })
    );
  });

  it('lists and precisely revokes one remote desktop participant', async () => {
    const participant = {
      connectionId: 'rdc_1234567890abcdefghij',
      sessionId: 'ses_1234567890abcdef',
      contextEpoch: 3,
      actorId: 'viewer-test',
      accessMode: 'COLLABORATIVE',
      viewOnly: true,
      state: 'CONNECTED',
      reason: 'RFB_UPSTREAM_CONNECTED',
      observedAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ items: [participant], onlineCount: 1 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            items: [{ ...participant, state: 'DISCONNECTED' }],
            total: 1,
            limit: 20,
            nextCursor: null,
            hasMore: false,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        )
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ ...participant, state: 'REVOKE_REQUESTED' }),
          { status: 202, headers: { 'Content-Type': 'application/json' } }
        )
      );
    vi.stubGlobal('fetch', fetchMock);

    await getRemoteDesktopParticipants(participant.sessionId, 'tenant-test');
    await getRemoteDesktopParticipantHistory(
      participant.sessionId,
      20,
      'cursor-test',
      'tenant-test'
    );
    await revokeRemoteDesktopParticipant(
      participant.sessionId,
      participant.connectionId,
      'desktop-revoke-request-1',
      'tenant-test',
      'admin-test'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      `/api/v1/sessions/${participant.sessionId}/desktop-participants`,
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-Tenant-Id': 'tenant-test' }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      `/api/v1/sessions/${participant.sessionId}/desktop-participants/history?limit=20&cursor=cursor-test`,
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-Tenant-Id': 'tenant-test' }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      `/api/v1/sessions/${participant.sessionId}/desktop-participants/${participant.connectionId}:revoke`,
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'desktop-revoke-request-1',
          'X-Actor-Id': 'admin-test',
        }),
      })
    );
  });

  it('captures and redeems purpose-bound screenshot evidence through distinct requests', async () => {
    const capture = {
      captureId: 'cap_1234567890abcdef',
      sessionId: 'ses_1234567890abcdef',
      purpose: 'SUPPORT_DIAGNOSTICS',
      state: 'EXECUTING',
      commandId: 'cmd_1234567890abcdef',
      createdAt: new Date().toISOString(),
    };
    const grant = {
      grantId: 'egr_1234567890abcdef',
      sessionId: capture.sessionId,
      evidenceId: 'evd_1234567890abcdef',
      purpose: capture.purpose,
      state: 'ISSUED',
      expiresAt: new Date(Date.now() + 300_000).toISOString(),
      createdAt: new Date().toISOString(),
    };
    const access = {
      grantId: grant.grantId,
      evidenceId: grant.evidenceId,
      downloadUrl: 'https://objects.example.test/evidence?signature=redacted',
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(capture), {
          status: 202,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(grant), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(access), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      );
    vi.stubGlobal('fetch', fetchMock);

    await captureSessionEvidence(
      capture.sessionId,
      'SUPPORT_DIAGNOSTICS',
      'capture-idempotency',
      'tenant-test'
    );
    await createSessionEvidenceAccessGrant(
      grant.sessionId,
      grant.evidenceId,
      'SUPPORT_DIAGNOSTICS',
      'grant-idempotency',
      'tenant-test'
    );
    await redeemSessionEvidenceAccessGrant(
      grant.sessionId,
      grant.grantId,
      'tenant-test'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      `/api/v1/sessions/${capture.sessionId}/evidence:capture`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ purpose: 'SUPPORT_DIAGNOSTICS' }),
        headers: expect.objectContaining({
          'Idempotency-Key': 'capture-idempotency',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      `/api/v1/sessions/${grant.sessionId}/evidence/${grant.evidenceId}/access-grants`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ purpose: 'SUPPORT_DIAGNOSTICS' }),
        headers: expect.objectContaining({
          'Idempotency-Key': 'grant-idempotency',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      `/api/v1/sessions/${grant.sessionId}/evidence-access-grants/${grant.grantId}:redeem`,
      expect.objectContaining({ method: 'POST' })
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

    await getSessionApplicationBinding('ses_1234567890abcdef', 'tenant-test');
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

  it('creates and reads a Safe Point controlled proxy rebind workflow', async () => {
    const operation = {
      workflowId: 'prb_1234567890abcdef1234567890abcdef',
      operationId: 'op_1234567890abcdefghij',
      phase: 'CHECKPOINTING',
      createdAt: new Date().toISOString(),
    };
    const workflow = {
      ...operation,
      sessionId: 'ses_1234567890abcdef',
      sourceBindingProfileId: 'pbind_source1234567890',
      targetBindingProfileId: 'pbind_target1234567890',
      targetBindingVersion: 2,
      hibernateOperationId: operation.operationId,
      restoreOperationId: null,
      resyncRequestId: null,
      recoveryResult: null,
      failureReason: null,
      requestedBy: 'admin-test',
      reason: 'Approved egress rotation',
      requestId: 'request-test',
      updatedAt: operation.createdAt,
      completedAt: null,
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(operation), {
          status: 202,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(workflow), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      );
    vi.stubGlobal('fetch', fetchMock);

    await rebindSessionProxy(
      'ses_1234567890abcdef',
      {
        targetBindingProfileId: 'pbind_target1234567890',
        reason: 'Approved egress rotation',
      },
      'proxy-rebind-1',
      'tenant-test'
    );
    await getSessionProxyRebind('ses_1234567890abcdef', 'tenant-test');

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/sessions/ses_1234567890abcdef/proxy-binding:rebind',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          targetBindingProfileId: 'pbind_target1234567890',
          reason: 'Approved egress rotation',
        }),
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'Idempotency-Key': 'proxy-rebind-1',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/sessions/ses_1234567890abcdef/proxy-rebind',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
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

  it('lists, compares, and restores an immutable recovery contract revision', async () => {
    const now = new Date().toISOString();
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ items: [], total: 0, currentVersion: 3 }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }
        )
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            contractId: 'arc_1234567890abcdefghij',
            applicationId: 'crm.singapore',
            fromVersion: 1,
            toVersion: 3,
            changes: [],
            total: 0,
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }
        )
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            contractId: 'arc_1234567890abcdefghij',
            applicationId: 'crm.singapore',
            version: 4,
            expectedOrigins: ['https://crm.example.test'],
            readyRoutePrefixes: [],
            loginRoutePrefixes: [],
            requiredTargets: [],
            loginTargets: [],
            permissionDeniedTargets: [],
            accountMismatchTargets: [],
            requiredExtensionIds: [],
            allowDepthLimited: false,
            recoveryAction: 'NONE',
            maximumAutoRecovery: 0,
            enabled: true,
            approvalState: 'DRAFT',
            createdAt: now,
            updatedAt: now,
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }
        )
      );
    vi.stubGlobal('fetch', fetchMock);

    await listRecoveryContractRevisions('crm.singapore', 'tenant-test');
    await getRecoveryContractDiff('crm.singapore', 1, 3, 'tenant-test');
    await restoreRecoveryContractRevision(
      'crm.singapore',
      {
        expectedCurrentVersion: 3,
        sourceContractVersion: 1,
        reason: 'Restore known-good route gate',
      },
      'restore-contract-1',
      'tenant-test'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/applications/crm.singapore/recovery-contract/revisions',
      expect.any(Object)
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/applications/crm.singapore/recovery-contract/revisions/1/diff?compareToVersion=3',
      expect.any(Object)
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/v1/applications/crm.singapore/recovery-contract:restore',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          expectedCurrentVersion: 3,
          sourceContractVersion: 1,
          reason: 'Restore known-good route gate',
        }),
        headers: expect.objectContaining({
          'Idempotency-Key': 'restore-contract-1',
        }),
      })
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

  it('loads a Challenge preview and authorizes exactly one bound Human Assist click', async () => {
    const eventId = 'chl_1234567890abcdefghij';
    const preview = {
      challenge: {
        challengeEventId: eventId,
        sessionId: 'ses_1234567890abcdef',
        contextEpoch: 2,
        stateVersion: 12,
        targetRevision: 4,
        confidence: 0.99,
        evidence: {},
        suspectedType: 'SINGLE_CLICK',
        accessOutcome: 'CHALLENGE_CONFIRMED',
        targetRef: 'target:4:captcha',
        targetSummary: 'Verification checkbox',
        status: 'CONFIRMED',
        oneClickEligible: true,
        detectedAt: new Date().toISOString(),
        authorizationDeadline: new Date(Date.now() + 60_000).toISOString(),
        expiresAt: new Date(Date.now() + 120_000).toISOString(),
        updatedAt: new Date().toISOString(),
      },
      previewHash: 'a'.repeat(64),
      highlight: { x: 10, y: 20, width: 30, height: 40 },
      fresh: true,
      canAuthorize: true,
      previewedAt: new Date().toISOString(),
    };
    const intent = {
      intentId: 'hint_1234567890abcdefghij',
      challengeEventId: eventId,
      sessionId: preview.challenge.sessionId,
      userId: 'user-test',
      contextEpoch: 2,
      stateVersion: 12,
      targetRevision: 4,
      allowedTargetRef: 'target:4:captcha',
      allowedActionCount: 1,
      consumedCount: 1,
      authorizationEventId: 'evt_1234567890abcdef',
      operationId: 'op_1234567890abcdef',
      requestId: 'req-test',
      state: 'EXECUTING',
      expiresAt: preview.challenge.expiresAt,
      createdAt: preview.previewedAt,
      consumedAt: preview.previewedAt,
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ items: [preview.challenge] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(preview), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(intent), {
          status: 202,
          headers: { 'Content-Type': 'application/json' },
        })
      );
    vi.stubGlobal('fetch', fetchMock);

    await getSessionChallenges(preview.challenge.sessionId, 'tenant-test');
    await getChallengePreview(eventId, 'tenant-test', 'user-test');
    await authorizeHumanAssist(
      eventId,
      {
        previewHash: preview.previewHash,
        expectedStateVersion: 12,
        expectedTargetRevision: 4,
      },
      'idem-human-assist-1',
      'tenant-test',
      'user-test'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      `/api/v1/challenges/${eventId}/assist-authorizations`,
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'X-Actor-Id': 'user-test',
          'Idempotency-Key': 'idem-human-assist-1',
        }),
        body: JSON.stringify({
          previewHash: preview.previewHash,
          expectedStateVersion: 12,
          expectedTargetRevision: 4,
        }),
      })
    );
  });

  it('reads and adjusts the bounded Challenge visual automation budget', async () => {
    const sessionId = 'ses_1234567890abcdef';
    const policy = {
      sessionId,
      enabled: true,
      maximumAttempts: 3,
      minimumConfidence: 0.85,
      allowMultiClick: true,
      allowSlide: true,
      updatedAt: new Date().toISOString(),
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(policy), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ ...policy, maximumAttempts: 5 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await getChallengeAutomationPolicy(sessionId, 'tenant-test');
    await updateChallengeAutomationPolicy(
      sessionId,
      {
        enabled: true,
        maximumAttempts: 5,
        minimumConfidence: 0.85,
        allowMultiClick: true,
        allowSlide: true,
      },
      'tenant-test',
      'operator-test'
    );
    expect(
      await getCurrentChallengeAutomationRun(sessionId, 'tenant-test')
    ).toBeNull();

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      `/api/v1/sessions/${sessionId}/challenge-automation/policy`,
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'X-Actor-Id': 'operator-test',
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

  it('consumes lifecycle and Browser State changes from the unified Session SSE', async () => {
    const encoder = new TextEncoder();
    const payload =
      'id: 20\nevent: session-stream-ready\ndata: {"cursor":20,"resetRequired":false,"connectedAt":"2026-07-30T00:00:00Z"}\n\n' +
      'id: 21\nevent: session-change\ndata: {"sequence":21,"changeType":"SESSION","entityId":"ses_1234567890abcdef","occurredAt":"2026-07-30T00:00:01Z","replayed":false}\n\n' +
      'id: 22\nevent: session-change\ndata: {"sequence":22,"changeType":"BROWSER_STATE","entityId":"ses_1234567890abcdef:1:8","occurredAt":"2026-07-30T00:00:02Z","replayed":false}\n\n' +
      'id: 23\nevent: session-change\ndata: {"sequence":23,"changeType":"AUDIT_EVENT","entityId":"aud_1234567890abcdef","occurredAt":"2026-07-30T00:00:03Z","replayed":false}\n\n' +
      'id: 24\nevent: session-change\ndata: {"sequence":24,"changeType":"OPERATION","entityId":"op_1234567890abcdef","occurredAt":"2026-07-30T00:00:04Z","replayed":false}\n\n' +
      'id: 25\nevent: session-change\ndata: {"sequence":25,"changeType":"AGENT_TASK","entityId":"agt_1234567890abcdef","occurredAt":"2026-07-30T00:00:05Z","replayed":false}\n\n';
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(payload));
        controller.close();
      },
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(body, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const changes: string[] = [];

    await streamSessionChanges({
      sessionId: 'ses_1234567890abcdef',
      tenantId: 'tenant-test',
      lastEventId: '19',
      signal: new AbortController().signal,
      onOpen: vi.fn(),
      onControl: vi.fn(),
      onChange: (change) => changes.push(change.changeType),
    });

    expect(changes).toEqual([
      'SESSION',
      'BROWSER_STATE',
      'AUDIT_EVENT',
      'OPERATION',
      'AGENT_TASK',
    ]);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions/ses_1234567890abcdef/event-stream',
      expect.objectContaining({
        headers: expect.objectContaining({
          'Last-Event-ID': '19',
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
