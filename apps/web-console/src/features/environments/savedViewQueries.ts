import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createEnvironmentSavedView,
  deleteEnvironmentSavedView,
  listEnvironmentSavedViews,
  updateEnvironmentSavedView,
} from '@/api/savedView';
import { currentActorId, currentTenantId } from '@/api/session';
import type {
  CreateEnvironmentSavedViewRequest,
  EnvironmentSavedView,
  UpdateEnvironmentSavedViewRequest,
} from '@/types/savedView';

export const savedViewKeys = {
  all: ['environment-saved-views'] as const,
  list: (tenantId: string, actorId: string) =>
    [...savedViewKeys.all, 'list', tenantId, actorId] as const,
};

export function useEnvironmentSavedViews() {
  const tenantId = currentTenantId();
  const actorId = currentActorId();
  return useQuery({
    queryKey: savedViewKeys.list(tenantId, actorId),
    queryFn: ({ signal }) => listEnvironmentSavedViews(signal),
  });
}

function useSavedViewMutation<T>(
  mutationFn: (variables: T) => Promise<unknown>
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: savedViewKeys.all }),
  });
}

export function useCreateEnvironmentSavedView() {
  return useSavedViewMutation((body: CreateEnvironmentSavedViewRequest) =>
    createEnvironmentSavedView(body, `saved-view-create-${crypto.randomUUID()}`)
  );
}

export function useUpdateEnvironmentSavedView() {
  return useSavedViewMutation(
    ({
      savedView,
      body,
    }: {
      savedView: EnvironmentSavedView;
      body: UpdateEnvironmentSavedViewRequest;
    }) =>
      updateEnvironmentSavedView(
        savedView.savedViewId,
        body,
        `saved-view-update-${crypto.randomUUID()}`
      )
  );
}

export function useDeleteEnvironmentSavedView() {
  return useSavedViewMutation((savedView: EnvironmentSavedView) =>
    deleteEnvironmentSavedView(
      savedView.savedViewId,
      savedView.version,
      `saved-view-delete-${crypto.randomUUID()}`
    )
  );
}
