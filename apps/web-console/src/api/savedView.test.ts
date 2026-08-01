import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  createEnvironmentSavedView,
  deleteEnvironmentSavedView,
  listEnvironmentSavedViews,
  updateEnvironmentSavedView,
} from './savedView';

const savedView = {
  savedViewId: 'svw_1234567890abcdef',
  name: 'Running CRM',
  scope: 'PERSONAL' as const,
  ownerActorId: 'user-local',
  primaryView: 'RUNNING' as const,
  sessionState: null,
  searchQuery: 'singapore',
  groupId: 'grp_1234567890abcdef',
  tagIds: ['tag_1234567890abcdef', 'tag_fedcba0987654321'],
  tagMatch: 'ALL' as const,
  showRuntimeColumn: true,
  showContextColumn: false,
  showOperationColumn: true,
  createdAt: '2026-07-30T00:00:00Z',
  updatedAt: '2026-07-30T00:00:00Z',
  version: 0,
};

afterEach(() => {
  vi.restoreAllMocks();
});

describe('Environment Saved View API', () => {
  it('uses authenticated context, idempotency and optimistic versioning', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ items: [savedView], total: 1 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(savedView), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ ...savedView, version: 1 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    const configuration = {
      name: savedView.name,
      primaryView: savedView.primaryView,
      sessionState: savedView.sessionState,
      searchQuery: savedView.searchQuery,
      groupId: savedView.groupId,
      tagIds: savedView.tagIds,
      tagMatch: savedView.tagMatch,
      showRuntimeColumn: savedView.showRuntimeColumn,
      showContextColumn: savedView.showContextColumn,
      showOperationColumn: savedView.showOperationColumn,
    };

    await listEnvironmentSavedViews();
    await createEnvironmentSavedView(
      { ...configuration, scope: 'PERSONAL' },
      'saved-create-1'
    );
    await updateEnvironmentSavedView(
      savedView.savedViewId,
      { ...configuration, expectedVersion: 0 },
      'saved-update-1'
    );
    await deleteEnvironmentSavedView(
      savedView.savedViewId,
      1,
      'saved-delete-1'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/environment-saved-views',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'saved-create-1',
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
    expect(
      JSON.parse(
        (fetchMock.mock.calls[1]?.[1]?.body as string | undefined) ?? '{}'
      )
    ).toMatchObject({
      groupId: savedView.groupId,
      tagIds: savedView.tagIds,
      tagMatch: 'ALL',
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      `/api/v1/environment-saved-views/${savedView.savedViewId}`,
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({
          'Idempotency-Key': 'saved-update-1',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      `/api/v1/environment-saved-views/${savedView.savedViewId}?expectedVersion=1`,
      expect.objectContaining({
        method: 'DELETE',
        headers: expect.objectContaining({
          'Idempotency-Key': 'saved-delete-1',
        }),
      })
    );
  });
});
