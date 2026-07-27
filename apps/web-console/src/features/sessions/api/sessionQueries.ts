import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createSession,
  getBrowserState,
  getSession,
  listSessions,
  releaseHumanTakeover,
  requestHumanTakeover,
  resyncBrowserState,
  startSession,
  terminateSession,
} from '@/api/session';
import type {
  CreateSessionRequest,
  SessionState,
  StateResyncRequest,
} from '@/types/session';

export const sessionKeys = {
  all: ['sessions'] as const,
  list: (params: {
    state?: SessionState;
    query?: string;
    limit: number;
    offset: number;
  }) => [...sessionKeys.all, 'list', params] as const,
  detail: (sessionId: string) =>
    [...sessionKeys.all, 'detail', sessionId] as const,
  browserState: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'browser-state'] as const,
};

export function useSessions(params: {
  state?: SessionState;
  query?: string;
  limit?: number;
  offset?: number;
}) {
  const limit = params.limit ?? 20;
  const offset = params.offset ?? 0;
  return useQuery({
    queryKey: sessionKeys.list({
      state: params.state,
      query: params.query,
      limit,
      offset,
    }),
    queryFn: ({ signal }) =>
      listSessions({
        state: params.state,
        query: params.query,
        limit,
        offset,
        signal,
      }),
  });
}

export function useSession(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.detail(sessionId),
    queryFn: ({ signal }) => getSession(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
    refetchInterval: (query) => {
      const session = query.state.data;
      if (!session) return false;
      return session.currentOperation?.state === 'ACTIVE' ||
        [
          'STARTING',
          'RUNNING',
          'DEGRADED',
          'RECOVERING',
          'TERMINATING',
        ].includes(session.state)
        ? 2_000
        : false;
    },
  });
}

export function useBrowserState(sessionId: string, enabled: boolean) {
  return useQuery({
    queryKey: sessionKeys.browserState(sessionId),
    queryFn: ({ signal }) => getBrowserState(sessionId, undefined, signal),
    enabled: Boolean(sessionId) && enabled,
    refetchInterval: enabled ? 2_000 : false,
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

export function useRequestHumanTakeover(sessionId: string) {
  return useSessionOperation(sessionId, () => requestHumanTakeover(sessionId));
}

export function useReleaseHumanTakeover(sessionId: string) {
  return useSessionOperation(sessionId, () => releaseHumanTakeover(sessionId));
}

export function useResyncBrowserState(sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: StateResyncRequest) =>
      resyncBrowserState(
        sessionId,
        request,
        `state-resync-${crypto.randomUUID()}`
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: sessionKeys.browserState(sessionId),
      }),
  });
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
        queryClient.invalidateQueries({
          queryKey: sessionKeys.browserState(sessionId),
        }),
        queryClient.invalidateQueries({ queryKey: sessionKeys.all }),
      ]);
    },
  });
}
