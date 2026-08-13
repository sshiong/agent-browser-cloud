import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  createProfile,
  createProfileExportGrant,
  importProfileCheckpoint,
  listProfileImports,
  listProfiles,
  redeemProfileExportGrant,
} from './profile';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('profile API', () => {
  it('lists Profiles within the selected tenant', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ items: [], total: 0 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );

    await listProfiles('tenant-test');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/profiles',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-Tenant-Id': 'tenant-test' }),
      })
    );
  });

  it('creates a Profile through the real control-plane contract', async () => {
    const profile = {
      profileId: 'profile-test',
      tenantId: 'tenant-test',
      name: 'Test',
      description: null,
      latestCheckpointId: null,
      latestCheckpointEpoch: null,
      profileWriteEpoch: 0,
      coreSizeBytes: 0,
      checkpointFileCount: 0,
      restoreStatus: 'EMPTY',
      state: 'ACTIVE',
      createdAt: '2026-07-26T00:00:00Z',
      updatedAt: '2026-07-26T00:00:00Z',
      lastCheckpointAt: null,
    };
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(profile), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      })
    );

    await createProfile(
      { profileId: 'profile-test', name: 'Test' },
      'tenant-test'
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/profiles',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ profileId: 'profile-test', name: 'Test' }),
      })
    );
  });

  it('streams a checkpoint as multipart without overriding the browser boundary', async () => {
    const imported = {
      importId: 'pim_1234567890abcdef',
      operationId: 'op_1234567890abcdef',
      profileId: 'profile-imported',
      profileName: 'Imported',
      runtimeBuildId: 'runtime-stable',
      archiveSha256: 'a'.repeat(64),
      archiveSizeBytes: 3,
      state: 'COMMITTED',
      nodeId: 'node-one',
      checkpointId: 'chk_1234567890abcdef',
      checkpointEpoch: 1,
      profileWriteEpoch: 0,
      coreSizeBytes: 3,
      checkpointFileCount: 1,
      errorCode: null,
      requestId: 'req-one',
      createdAt: '2026-07-30T00:00:00Z',
      updatedAt: '2026-07-30T00:00:00Z',
      completedAt: '2026-07-30T00:00:00Z',
    };
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(imported), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      })
    );

    await importProfileCheckpoint(
      {
        profileId: 'profile-imported',
        profileName: 'Imported',
        runtimeBuildId: 'runtime-stable',
        archiveSha256: 'a'.repeat(64),
        archive: new File(['abc'], 'profile.tar.zst'),
      },
      'profile-import-key',
      'tenant-test'
    );

    const fetchCall = fetchMock.mock.calls[0];
    expect(fetchCall).toBeDefined();
    const [, init] = fetchCall!;
    expect(init).toEqual(
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'profile-import-key',
          'X-Tenant-Id': 'tenant-test',
        }),
        body: expect.any(FormData),
      })
    );
    expect(
      (init?.headers as Record<string, string>)['Content-Type']
    ).toBeUndefined();
    expect((init?.body as FormData).get('profileId')).toBe('profile-imported');
  });

  it('lists only actor-owned Profile Import jobs', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ items: [], total: 0 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );

    await listProfileImports('tenant-test');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/profile-imports?limit=20',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-Tenant-Id': 'tenant-test' }),
      })
    );
  });

  it('creates and redeems a purpose-bound one-time Profile export grant', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            grantId: 'pxg_1234567890abcdef',
            profileId: 'profile-test',
            checkpointId: 'chk_1234567890abcdef',
            checkpointEpoch: 2,
            purpose: 'TENANT_BACKUP',
            state: 'ISSUED',
            expiresAt: '2026-08-13T00:05:00Z',
            createdAt: '2026-08-13T00:00:00Z',
          }),
          { status: 201, headers: { 'Content-Type': 'application/json' } }
        )
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            grantId: 'pxg_1234567890abcdef',
            profileId: 'profile-test',
            checkpointId: 'chk_1234567890abcdef',
            archiveSha256: 'a'.repeat(64),
            archiveSizeBytes: 4096,
            downloadUrl:
              'https://objects.example.test/checkpoint?signature=redacted',
            expiresAt: '2026-08-13T00:01:00Z',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        )
      );

    const grant = await createProfileExportGrant(
      'profile-test',
      'TENANT_BACKUP',
      'profile-export-key',
      'tenant-test'
    );
    await redeemProfileExportGrant(
      'profile-test',
      grant.grantId,
      'tenant-test'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/profiles/profile-test/export-grants',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'profile-export-key',
          'X-Tenant-Id': 'tenant-test',
        }),
        body: JSON.stringify({ purpose: 'TENANT_BACKUP' }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/profiles/profile-test/export-grants/pxg_1234567890abcdef:redeem',
      expect.objectContaining({ method: 'POST' })
    );
  });
});
