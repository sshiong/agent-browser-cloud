import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  cancelWorkspaceMetadataBatchOperation,
  createWorkspaceMetadataBatchOperation,
  getWorkspaceMetadataBatchOperation,
  listWorkspaceMetadataBatchOperations,
} from './workspaceMetadataBatch';
import type { WorkspaceMetadataBatchOperation } from '@/types/workspaceMetadataBatch';

const operation: WorkspaceMetadataBatchOperation = {
  batchOperationId: 'mbop_1234567890abcdef',
  action: 'ASSIGN_TAGS',
  state: 'ACCEPTED',
  selector: {
    tagIds: [],
    tagMatch: 'ANY',
    sessionIds: ['ses_1234567890abcdef'],
  },
  target: { tagIds: ['tag_1234567890abcdef'] },
  reason: 'Assign trusted tag',
  total: 1,
  accepted: 1,
  executing: 0,
  succeeded: 0,
  failed: 0,
  cancelled: 0,
  cancellationRequested: false,
  items: [
    {
      batchItemId: 'mbopi_1234567890abcdef',
      sessionId: 'ses_1234567890abcdef',
      ordinal: 0,
      state: 'ACCEPTED',
      attempt: 0,
      createdAt: '2026-08-01T00:00:00Z',
    },
  ],
  actorId: 'operator-a',
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
};

describe('Workspace metadata batch API', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockImplementation(
      async () =>
        new Response(JSON.stringify(operation), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it('creates, reads, lists and cancels durable metadata batches', async () => {
    await createWorkspaceMetadataBatchOperation(
      {
        action: 'ASSIGN_TAGS',
        selector: {
          tagIds: [],
          tagMatch: 'ANY',
          sessionIds: ['ses_1234567890abcdef'],
        },
        target: { tagIds: ['tag_1234567890abcdef'] },
        reason: 'Assign trusted tag',
        confirmed: true,
      },
      'metadata-create-key'
    );
    await getWorkspaceMetadataBatchOperation(operation.batchOperationId);
    await listWorkspaceMetadataBatchOperations(10);
    await cancelWorkspaceMetadataBatchOperation(
      operation.batchOperationId,
      'Cancel pending items',
      'metadata-cancel-key'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/workspace-metadata-batch-operations',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'metadata-create-key',
        }),
      })
    );
    expect(
      JSON.parse((fetchMock.mock.calls[0]?.[1]?.body as string) ?? '{}')
    ).toMatchObject({
      action: 'ASSIGN_TAGS',
      target: { tagIds: ['tag_1234567890abcdef'] },
      confirmed: true,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      `/api/v1/workspace-metadata-batch-operations/${operation.batchOperationId}`,
      expect.objectContaining({ signal: undefined })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/v1/workspace-metadata-batch-operations?limit=10',
      expect.objectContaining({ signal: undefined })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      `/api/v1/workspace-metadata-batch-operations/${operation.batchOperationId}:cancel`,
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'metadata-cancel-key',
        }),
      })
    );
  });
});
