import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createBreakGlassRequest,
  listAuditEvents,
  listBreakGlassRequests,
  listRuntimeBuilds,
  transitionBreakGlassRequest,
} from '@/api/platform';
import type { CreateBreakGlassRequest } from '@/types/platform';

export function useAuditEvents(eventType?: string) {
  return useQuery({
    queryKey: ['audit-events', eventType ?? 'all'],
    queryFn: ({ signal }) => listAuditEvents(eventType, signal),
    refetchInterval: 5000,
  });
}

export function useRuntimeBuilds() {
  return useQuery({
    queryKey: ['runtime-builds'],
    queryFn: ({ signal }) => listRuntimeBuilds(signal),
  });
}

export function useBreakGlassRequests() {
  return useQuery({
    queryKey: ['break-glass-requests'],
    queryFn: ({ signal }) => listBreakGlassRequests(signal),
    refetchInterval: 5000,
  });
}

export function useCreateBreakGlassRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateBreakGlassRequest) =>
      createBreakGlassRequest(input),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['break-glass-requests'] }),
        queryClient.invalidateQueries({ queryKey: ['audit-events'] }),
      ]);
    },
  });
}

export function useTransitionBreakGlassRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      requestId,
      transition,
    }: {
      requestId: string;
      transition: 'approve' | 'reject' | 'revoke' | 'review';
    }) => transitionBreakGlassRequest(requestId, transition),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['break-glass-requests'] }),
        queryClient.invalidateQueries({ queryKey: ['audit-events'] }),
      ]);
    },
  });
}
