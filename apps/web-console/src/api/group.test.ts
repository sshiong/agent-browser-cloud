import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  assignSessionToGroup,
  createWorkspaceGroup,
  deleteWorkspaceGroup,
  listWorkspaceGroups,
} from './group';

const group = {
  groupId: 'grp_1234567890abcdef',
  name: 'Operations',
  description: null,
  color: '#35D6BE',
  defaultOnMaximumReached: 'PAUSE_AGENT',
  defaultAllowMigration: true,
  defaultAllowHibernate: true,
  sessions: [],
  sessionCount: 0,
  createdBy: 'user-local',
  createdAt: '2026-07-28T00:00:00Z',
  updatedAt: '2026-07-28T00:00:00Z',
};

afterEach(() => {
  vi.restoreAllMocks();
});

describe('Workspace Group API', () => {
  it('uses authenticated tenant context and idempotency for mutations', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ items: [], unassignedSessions: [], total: 0 }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        )
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(group), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(group), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    await listWorkspaceGroups();
    await createWorkspaceGroup(
      {
        name: 'Operations',
        color: '#35D6BE',
        defaultOnMaximumReached: 'PAUSE_AGENT',
        defaultAllowMigration: true,
        defaultAllowHibernate: true,
      },
      'group-create-1'
    );
    await assignSessionToGroup(
      group.groupId,
      'ses_1234567890abcdef',
      'group-assign-1'
    );
    await deleteWorkspaceGroup(group.groupId, 'group-delete-1');

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/groups',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': 'group-create-1',
          'X-Tenant-Id': 'tenant-local',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      `/api/v1/groups/${group.groupId}/sessions/ses_1234567890abcdef`,
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({
          'Idempotency-Key': 'group-assign-1',
        }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      `/api/v1/groups/${group.groupId}`,
      expect.objectContaining({
        method: 'DELETE',
        headers: expect.objectContaining({
          'Idempotency-Key': 'group-delete-1',
        }),
      })
    );
  });
});
