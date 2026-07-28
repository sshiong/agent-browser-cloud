import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  assignSessionToTag,
  createWorkspaceTag,
  deleteWorkspaceTag,
  listWorkspaceTags,
} from './tag';

const tag = {
  tagId: 'tag_1234567890abcdef',
  name: 'Production',
  description: null,
  color: '#35D6BE',
  sessions: [],
  sessionCount: 0,
  createdBy: 'user-local',
  createdAt: '2026-07-28T00:00:00Z',
  updatedAt: '2026-07-28T00:00:00Z',
};

afterEach(() => {
  vi.restoreAllMocks();
});

describe('Workspace Tag API', () => {
  it('uses authenticated tenant context and idempotency for mutations', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ items: [], sessions: [], total: 0 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(tag), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(tag), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    await listWorkspaceTags();
    await createWorkspaceTag(
      { name: 'Production', color: '#35D6BE' },
      'tag-create-1'
    );
    await assignSessionToTag(tag.tagId, 'ses_1234567890abcdef', 'tag-assign-1');
    await deleteWorkspaceTag(tag.tagId, 'tag-delete-1');

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/tags',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'tag-create-1',
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      `/api/v1/tags/${tag.tagId}/sessions/ses_1234567890abcdef`,
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({
          'Idempotency-Key': 'tag-assign-1',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      `/api/v1/tags/${tag.tagId}`,
      expect.objectContaining({
        method: 'DELETE',
        headers: expect.objectContaining({
          'Idempotency-Key': 'tag-delete-1',
        }),
      })
    );
  });
});
