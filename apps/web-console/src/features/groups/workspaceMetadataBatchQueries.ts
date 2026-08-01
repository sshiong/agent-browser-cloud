import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  cancelWorkspaceMetadataBatchOperation,
  createWorkspaceMetadataBatchOperation,
  getWorkspaceMetadataBatchOperation,
} from '@/api/workspaceMetadataBatch';
import type { CreateWorkspaceMetadataBatchOperationRequest } from '@/types/workspaceMetadataBatch';

export const workspaceMetadataBatchKeys = {
  all: ['workspace-metadata-batch-operations'] as const,
  detail: (batchOperationId: string) =>
    [...workspaceMetadataBatchKeys.all, batchOperationId] as const,
};

export function useWorkspaceMetadataBatchOperation(batchOperationId?: string) {
  return useQuery({
    queryKey: workspaceMetadataBatchKeys.detail(batchOperationId ?? ''),
    queryFn: ({ signal }) =>
      getWorkspaceMetadataBatchOperation(batchOperationId ?? '', signal),
    enabled: Boolean(batchOperationId),
    refetchInterval: (query) =>
      query.state.data &&
      !['SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED'].includes(
        query.state.data.state
      )
        ? 1_000
        : false,
  });
}

export function useCreateWorkspaceMetadataBatchOperation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateWorkspaceMetadataBatchOperationRequest) =>
      createWorkspaceMetadataBatchOperation(
        body,
        `workspace-metadata-batch-${crypto.randomUUID()}`
      ),
    onSuccess: (operation) => {
      queryClient.setQueryData(
        workspaceMetadataBatchKeys.detail(operation.batchOperationId),
        operation
      );
    },
  });
}

export function useCancelWorkspaceMetadataBatchOperation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      batchOperationId,
      reason,
    }: {
      batchOperationId: string;
      reason: string;
    }) =>
      cancelWorkspaceMetadataBatchOperation(
        batchOperationId,
        reason,
        `workspace-metadata-batch-cancel-${crypto.randomUUID()}`
      ),
    onSuccess: (operation) => {
      queryClient.setQueryData(
        workspaceMetadataBatchKeys.detail(operation.batchOperationId),
        operation
      );
    },
  });
}
