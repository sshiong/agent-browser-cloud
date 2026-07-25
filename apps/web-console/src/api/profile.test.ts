import { afterEach, describe, expect, it, vi } from 'vitest';
import { createProfile, listProfiles } from './profile';

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
});
