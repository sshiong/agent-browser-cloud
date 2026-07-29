import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  commitEnvironmentImport,
  listEnvironmentImports,
  previewEnvironmentImport,
} from './environmentImport';
import type {
  EnvironmentImport,
  PreviewEnvironmentImportRequest,
} from '@/types/environmentImport';

const manifest: PreviewEnvironmentImportRequest = {
  schemaVersion: 1,
  name: 'CRM fleet',
  environments: [
    {
      displayName: 'CRM Singapore',
      profileId: 'profile-sg',
      resourcePolicy: { mode: 'AUTO' },
    },
  ],
};

const environmentImport: EnvironmentImport = {
  importId: 'imp_1234567890abcdef',
  name: manifest.name,
  schemaVersion: 1,
  manifestHash: 'a'.repeat(64),
  state: 'VALIDATED',
  totalCount: 1,
  readyCount: 1,
  succeededCount: 0,
  items: [],
  createdAt: '2026-07-30T00:00:00Z',
  updatedAt: '2026-07-30T00:00:00Z',
  version: 0,
};

afterEach(() => {
  vi.restoreAllMocks();
});

describe('Environment Import API', () => {
  it('uses actor identity, idempotency, and optimistic commit version', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ items: [], total: 0 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(environmentImport), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            ...environmentImport,
            state: 'COMMITTED',
            succeededCount: 1,
          }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }
        )
      );

    await listEnvironmentImports();
    await previewEnvironmentImport(manifest, 'import-preview-1');
    await commitEnvironmentImport(environmentImport, 'import-commit-1');

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/environment-imports:preview',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'import-preview-1',
          'X-Tenant-Id': 'tenant-local',
          'X-Actor-Id': 'user-local',
        }),
        body: JSON.stringify(manifest),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      `/api/v1/environment-imports/${environmentImport.importId}:commit`,
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'import-commit-1',
        }),
        body: JSON.stringify({ expectedVersion: 0 }),
      })
    );
  });
});
