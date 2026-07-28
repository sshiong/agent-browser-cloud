import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  assignSessionToTag,
  createWorkspaceTag,
  deleteWorkspaceTag,
  listWorkspaceTags,
  unassignSessionFromTag,
  updateWorkspaceTag,
} from '@/api/tag';
import type { WorkspaceTagRequest } from '@/types/tag';

export const tagKeys = {
  all: ['workspace-tags'] as const,
  list: () => [...tagKeys.all, 'list'] as const,
};

export function useWorkspaceTags() {
  return useQuery({
    queryKey: tagKeys.list(),
    queryFn: ({ signal }) => listWorkspaceTags(signal),
  });
}

function useTagMutation<T>(mutationFn: (variables: T) => Promise<unknown>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: tagKeys.all });
      void queryClient.invalidateQueries({ queryKey: ['sessions'] });
    },
  });
}

export function useCreateWorkspaceTag() {
  return useTagMutation((body: WorkspaceTagRequest) =>
    createWorkspaceTag(body, `tag-create-${crypto.randomUUID()}`)
  );
}

export function useUpdateWorkspaceTag() {
  return useTagMutation(
    ({ tagId, body }: { tagId: string; body: WorkspaceTagRequest }) =>
      updateWorkspaceTag(tagId, body, `tag-update-${crypto.randomUUID()}`)
  );
}

export function useDeleteWorkspaceTag() {
  return useTagMutation((tagId: string) =>
    deleteWorkspaceTag(tagId, `tag-delete-${crypto.randomUUID()}`)
  );
}

export function useAssignSessionToTag() {
  return useTagMutation(
    ({ tagId, sessionId }: { tagId: string; sessionId: string }) =>
      assignSessionToTag(tagId, sessionId, `tag-assign-${crypto.randomUUID()}`)
  );
}

export function useUnassignSessionFromTag() {
  return useTagMutation(
    ({ tagId, sessionId }: { tagId: string; sessionId: string }) =>
      unassignSessionFromTag(
        tagId,
        sessionId,
        `tag-unassign-${crypto.randomUUID()}`
      )
  );
}
