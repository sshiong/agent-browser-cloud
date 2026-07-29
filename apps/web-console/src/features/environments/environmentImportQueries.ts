import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  commitEnvironmentImport,
  listEnvironmentImports,
  previewEnvironmentImport,
} from '@/api/environmentImport';
import { currentActorId, currentTenantId } from '@/api/session';
import type {
  EnvironmentImport,
  PreviewEnvironmentImportRequest,
} from '@/types/environmentImport';
import { sessionKeys } from '@/features/sessions/api/sessionQueries';

export const environmentImportKeys = {
  all: ['environment-imports'] as const,
  list: (tenantId: string, actorId: string) =>
    [...environmentImportKeys.all, 'list', tenantId, actorId] as const,
};

export function useEnvironmentImports(enabled: boolean) {
  return useQuery({
    queryKey: environmentImportKeys.list(currentTenantId(), currentActorId()),
    queryFn: ({ signal }) => listEnvironmentImports(signal),
    enabled,
  });
}

export function usePreviewEnvironmentImport() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: PreviewEnvironmentImportRequest) =>
      previewEnvironmentImport(
        body,
        `environment-import-preview-${crypto.randomUUID()}`
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: environmentImportKeys.all }),
  });
}

export function useCommitEnvironmentImport() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (environmentImport: EnvironmentImport) =>
      commitEnvironmentImport(
        environmentImport,
        `environment-import-commit-${crypto.randomUUID()}`
      ),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: environmentImportKeys.all }),
        queryClient.invalidateQueries({ queryKey: sessionKeys.all }),
      ]);
    },
  });
}
