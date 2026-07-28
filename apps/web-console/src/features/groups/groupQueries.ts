import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  assignSessionToGroup,
  createWorkspaceGroup,
  deleteWorkspaceGroup,
  listWorkspaceGroups,
  unassignSessionFromGroup,
  updateWorkspaceGroup,
} from '@/api/group';
import type { WorkspaceGroupRequest } from '@/types/group';

export const groupKeys = {
  all: ['workspace-groups'] as const,
  list: () => [...groupKeys.all, 'list'] as const,
};

export function useWorkspaceGroups() {
  return useQuery({
    queryKey: groupKeys.list(),
    queryFn: ({ signal }) => listWorkspaceGroups(signal),
  });
}

function useGroupMutation<T>(mutationFn: (variables: T) => Promise<unknown>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: groupKeys.all }),
  });
}

export function useCreateWorkspaceGroup() {
  return useGroupMutation((body: WorkspaceGroupRequest) =>
    createWorkspaceGroup(body, `group-create-${crypto.randomUUID()}`)
  );
}

export function useUpdateWorkspaceGroup() {
  return useGroupMutation(
    ({ groupId, body }: { groupId: string; body: WorkspaceGroupRequest }) =>
      updateWorkspaceGroup(groupId, body, `group-update-${crypto.randomUUID()}`)
  );
}

export function useDeleteWorkspaceGroup() {
  return useGroupMutation((groupId: string) =>
    deleteWorkspaceGroup(groupId, `group-delete-${crypto.randomUUID()}`)
  );
}

export function useAssignSessionToGroup() {
  return useGroupMutation(
    ({ groupId, sessionId }: { groupId: string; sessionId: string }) =>
      assignSessionToGroup(
        groupId,
        sessionId,
        `group-assign-${crypto.randomUUID()}`
      )
  );
}

export function useUnassignSessionFromGroup() {
  return useGroupMutation(
    ({ groupId, sessionId }: { groupId: string; sessionId: string }) =>
      unassignSessionFromGroup(
        groupId,
        sessionId,
        `group-unassign-${crypto.randomUUID()}`
      )
  );
}
