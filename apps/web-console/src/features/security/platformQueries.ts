import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createBreakGlassRequest,
  completeKeyRotationRequest,
  createKeyRotationRequest,
  endSecureDebugSession,
  listAuditEvents,
  listBreakGlassRequests,
  listKeyRotationRequests,
  listRuntimeBuilds,
  listSecureDebugSessions,
  readSecureDebugSnapshot,
  startSecureDebugSession,
  transitionBreakGlassRequest,
  transitionKeyRotationRequest,
} from '@/api/platform';
import type {
  CompleteKeyRotationRequest,
  CreateBreakGlassRequest,
  CreateKeyRotationRequest,
} from '@/types/platform';

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

export function useSecureDebugSessions() {
  return useQuery({
    queryKey: ['secure-debug-sessions'],
    queryFn: ({ signal }) => listSecureDebugSessions(signal),
    refetchInterval: 3000,
  });
}

export function useStartSecureDebugSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (requestId: string) => startSecureDebugSession(requestId),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['secure-debug-sessions'] }),
        queryClient.invalidateQueries({ queryKey: ['break-glass-requests'] }),
        queryClient.invalidateQueries({ queryKey: ['audit-events'] }),
      ]);
    },
  });
}

export function useReadSecureDebugSnapshot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (debugSessionId: string) =>
      readSecureDebugSnapshot(debugSessionId),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['secure-debug-sessions'] }),
        queryClient.invalidateQueries({ queryKey: ['audit-events'] }),
      ]);
    },
  });
}

export function useEndSecureDebugSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (debugSessionId: string) =>
      endSecureDebugSession(debugSessionId),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['secure-debug-sessions'] }),
        queryClient.invalidateQueries({ queryKey: ['audit-events'] }),
      ]);
    },
  });
}

export function useKeyRotationRequests() {
  return useQuery({
    queryKey: ['key-rotation-requests'],
    queryFn: ({ signal }) => listKeyRotationRequests(signal),
    refetchInterval: 5000,
  });
}

export function useCreateKeyRotationRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateKeyRotationRequest) =>
      createKeyRotationRequest(input),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['key-rotation-requests'] }),
        queryClient.invalidateQueries({ queryKey: ['audit-events'] }),
      ]);
    },
  });
}

export function useTransitionKeyRotationRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      rotationId,
      transition,
    }: {
      rotationId: string;
      transition: 'approve' | 'revoke';
    }) => transitionKeyRotationRequest(rotationId, transition),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['key-rotation-requests'] }),
        queryClient.invalidateQueries({ queryKey: ['audit-events'] }),
      ]);
    },
  });
}

export function useCompleteKeyRotationRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      rotationId,
      completion,
    }: {
      rotationId: string;
      completion: CompleteKeyRotationRequest;
    }) => completeKeyRotationRequest(rotationId, completion),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['key-rotation-requests'] }),
        queryClient.invalidateQueries({ queryKey: ['audit-events'] }),
      ]);
    },
  });
}
