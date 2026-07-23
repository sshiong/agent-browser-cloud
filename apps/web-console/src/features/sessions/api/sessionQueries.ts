import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createSession,
  getSession,
  listSessions,
  startSession,
  terminateSession,
} from '@/api/session';
import type { CreateSessionRequest, SessionState } from '@/types/session';

export const sessionKeys = {
  all: ['sessions'] as const,
  list: (params: { state?: SessionState; limit: number; offset: number }) =>
    [...sessionKeys.all, 'list', params] as const,
  detail: (sessionId: string) =>
    [...sessionKeys.all, 'detail', sessionId] as const,
};

export function useSessions(params: {
  state?: SessionState;
  limit?: number;
  offset?: number;
}) {
  const limit = params.limit ?? 20;
  const offset = params.offset ?? 0;
  return useQuery({
    queryKey: sessionKeys.list({ state: params.state, limit, offset }),
    queryFn: ({ signal }) =>
      listSessions({ state: params.state, limit, offset, signal }),
  });
}

export function useSession(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.detail(sessionId),
    queryFn: ({ signal }) => getSession(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
    refetchInterval: (query) =>
      query.state.data?.currentOperation?.state === 'ACTIVE' ? 2_000 : false,
  });
}

export function useCreateSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      request,
      idempotencyKey,
    }: {
      request: CreateSessionRequest;
      idempotencyKey: string;
    }) => createSession(request, idempotencyKey),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: sessionKeys.all }),
  });
}

export function useStartSession(sessionId: string) {
  return useSessionOperation(sessionId, () => startSession(sessionId));
}

export function useTerminateSession(sessionId: string) {
  return useSessionOperation(sessionId, () => terminateSession(sessionId));
}

function useSessionOperation(
  sessionId: string,
  operation: () => Promise<unknown>
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: operation,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: sessionKeys.detail(sessionId),
        }),
        queryClient.invalidateQueries({ queryKey: sessionKeys.all }),
      ]);
    },
  });
}
