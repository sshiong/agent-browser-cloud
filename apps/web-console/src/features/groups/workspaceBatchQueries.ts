import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  cancelWorkspaceBatchOperation,
  createWorkspaceBatchOperation,
  getWorkspaceBatchOperation,
} from '@/api/workspaceBatch';
import type { CreateWorkspaceBatchOperationRequest } from '@/types/workspaceBatch';

export const workspaceBatchKeys = {
  all: ['workspace-batch-operations'] as const,
  detail: (batchOperationId: string) =>
    [...workspaceBatchKeys.all, batchOperationId] as const,
};

export function useWorkspaceBatchOperation(batchOperationId?: string) {
  return useQuery({
    queryKey: workspaceBatchKeys.detail(batchOperationId ?? ''),
    queryFn: ({ signal }) =>
      getWorkspaceBatchOperation(batchOperationId ?? '', signal),
    enabled: Boolean(batchOperationId),
    refetchInterval: (query) =>
      query.state.data &&
      !['SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED'].includes(
        query.state.data.state
      )
        ? 2_000
        : false,
  });
}

export function useCreateWorkspaceBatchOperation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateWorkspaceBatchOperationRequest) =>
      createWorkspaceBatchOperation(
        body,
        `workspace-batch-${crypto.randomUUID()}`
      ),
    onSuccess: (operation) => {
      queryClient.setQueryData(
        workspaceBatchKeys.detail(operation.batchOperationId),
        operation
      );
    },
  });
}

export function useCancelWorkspaceBatchOperation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      batchOperationId,
      reason,
    }: {
      batchOperationId: string;
      reason: string;
    }) =>
      cancelWorkspaceBatchOperation(
        batchOperationId,
        reason,
        `workspace-batch-cancel-${crypto.randomUUID()}`
      ),
    onSuccess: (operation) => {
      queryClient.setQueryData(
        workspaceBatchKeys.detail(operation.batchOperationId),
        operation
      );
    },
  });
}
