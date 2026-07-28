import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getWorkspaceSettings, updateWorkspaceSettings } from '@/api/settings';
import type { WorkspaceSettingsRequest } from '@/types/settings';

export const workspaceSettingsKey = ['workspace-settings'] as const;

export function useWorkspaceSettings() {
  return useQuery({
    queryKey: workspaceSettingsKey,
    queryFn: ({ signal }) => getWorkspaceSettings(signal),
  });
}

export function useUpdateWorkspaceSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: WorkspaceSettingsRequest) =>
      updateWorkspaceSettings(
        request,
        `workspace-settings-${crypto.randomUUID()}`
      ),
    onSuccess: (settings) => {
      queryClient.setQueryData(workspaceSettingsKey, settings);
    },
  });
}
