import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  cancelWorkspaceBatchOperation,
  createWorkspaceBatchOperation,
  getWorkspaceBatchOperation,
  listWorkspaceBatchOperations,
} from './workspaceBatch';
import type { WorkspaceBatchOperation } from '@/types/workspaceBatch';

const operation: WorkspaceBatchOperation = {
  batchOperationId: 'bop_1234567890abcdef',
  action: 'START',
  state: 'ACCEPTED',
  selector: {
    groupId: 'grp_1234567890abcdef',
    tagIds: [],
    tagMatch: 'ANY',
    sessionIds: [],
  },
  total: 1,
  accepted: 1,
  executing: 0,
  succeeded: 0,
  failed: 0,
  cancelled: 0,
  cancellationRequested: false,
  items: [],
  actorId: 'user-local',
  createdAt: '2026-07-31T00:00:00Z',
  updatedAt: '2026-07-31T00:00:00Z',
};

afterEach(() => {
  vi.restoreAllMocks();
});

describe('Workspace batch operation API', () => {
  it('submits and cancels only through idempotent authoritative endpoints', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(
        new Response(JSON.stringify(operation), {
          status: 202,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(operation), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ items: [operation], total: 1 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ ...operation, cancellationRequested: true }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }
        )
      );

    await createWorkspaceBatchOperation(
      {
        action: 'START',
        selector: operation.selector,
        confirmed: false,
      },
      'batch-create-1'
    );
    await getWorkspaceBatchOperation(operation.batchOperationId);
    await listWorkspaceBatchOperations(10);
    await cancelWorkspaceBatchOperation(
      operation.batchOperationId,
      'Operator cancelled pending items',
      'batch-cancel-1'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/workspace-batch-operations',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'batch-create-1',
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      `/api/v1/workspace-batch-operations/${operation.batchOperationId}:cancel`,
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'batch-cancel-1',
        }),
      })
    );
  });
});
