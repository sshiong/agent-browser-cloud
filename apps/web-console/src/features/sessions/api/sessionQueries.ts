import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import {
  createSession,
  getBrowserState,
  getSessionResourceEvents,
  getSessionResources,
  getSessionSafePoint,
  getSessionMigration,
  getSession,
  listSessions,
  releaseHumanTakeover,
  requestHumanTakeover,
  resyncBrowserState,
  startSession,
  terminateSession,
  updateSessionResourcePolicy,
  streamSessionResourceChanges,
} from '@/api/session';
import type {
  CreateSessionRequest,
  ResourceStreamConnectionState,
  SessionState,
  StateResyncRequest,
  ResourcePolicyRequest,
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
  resources: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'resources'] as const,
  resourceEvents: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'resource-events'] as const,
  safePoint: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'safe-point'] as const,
  migration: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'migration'] as const,
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

export function useSessionResources(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.resources(sessionId),
    queryFn: ({ signal }) => getSessionResources(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useSessionResourceEvents(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.resourceEvents(sessionId),
    queryFn: ({ signal }) =>
      getSessionResourceEvents(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useSessionSafePoint(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.safePoint(sessionId),
    queryFn: ({ signal }) => getSessionSafePoint(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
    refetchInterval: (query) => {
      const expirations =
        query.state.data?.blockers
          .map((blocker) => blocker.expiresAt)
          .filter((value): value is string => Boolean(value))
          .map((value) => Date.parse(value))
          .filter(Number.isFinite) ?? [];
      if (!expirations.length) return false;
      return Math.max(250, Math.min(...expirations) - Date.now() + 100);
    },
  });
}

export function useSessionMigration(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.migration(sessionId),
    queryFn: ({ signal }) => getSessionMigration(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useSessionResourceStream(
  sessionId: string,
  enabled: boolean
): ResourceStreamConnectionState {
  const queryClient = useQueryClient();
  const [connectionState, setConnectionState] =
    useState<ResourceStreamConnectionState>(enabled ? 'CONNECTING' : 'IDLE');

  useEffect(() => {
    if (!enabled || !sessionId) {
      setConnectionState('IDLE');
      return;
    }
    const controller = new AbortController();
    let lastEventId: string | undefined;
    let reconnectAttempt = 0;

    const invalidateAllResourceViews = () =>
      Promise.all([
        queryClient.invalidateQueries({
          queryKey: sessionKeys.detail(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.resources(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.resourceEvents(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.safePoint(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.migration(sessionId),
        }),
      ]);

    const run = async () => {
      while (!controller.signal.aborted) {
        if (!navigator.onLine) {
          setConnectionState('OFFLINE');
          if (!(await waitForReconnect(2_000, controller.signal))) return;
          continue;
        }
        setConnectionState(
          reconnectAttempt === 0 ? 'CONNECTING' : 'RECONNECTING'
        );
        try {
          await streamSessionResourceChanges({
            sessionId,
            lastEventId,
            signal: controller.signal,
            onOpen: () => {
              setConnectionState(
                reconnectAttempt === 0 ? 'CONNECTING' : 'RECONNECTING'
              );
            },
            onControl: (control) => {
              reconnectAttempt = 0;
              lastEventId = String(control.cursor);
              setConnectionState('LIVE');
              void invalidateAllResourceViews();
            },
            onChange: (change) => {
              lastEventId = String(change.sequence);
              if (change.changeType === 'RESOURCE_SAMPLE') {
                void Promise.all([
                  queryClient.invalidateQueries({
                    queryKey: sessionKeys.resources(sessionId),
                  }),
                  queryClient.invalidateQueries({
                    queryKey: sessionKeys.safePoint(sessionId),
                  }),
                ]);
              } else {
                void invalidateAllResourceViews();
              }
            },
          });
        } catch {
          if (controller.signal.aborted) return;
        }
        reconnectAttempt += 1;
        setConnectionState(navigator.onLine ? 'RECONNECTING' : 'OFFLINE');
        const backoff =
          Math.min(30_000, 1_000 * 2 ** Math.min(reconnectAttempt - 1, 5)) +
          Math.round(Math.random() * 500);
        if (!(await waitForReconnect(backoff, controller.signal))) return;
      }
    };

    const markOffline = () => setConnectionState('OFFLINE');
    const markOnline = () => setConnectionState('RECONNECTING');
    window.addEventListener('offline', markOffline);
    window.addEventListener('online', markOnline);
    void run();
    return () => {
      controller.abort();
      window.removeEventListener('offline', markOffline);
      window.removeEventListener('online', markOnline);
    };
  }, [enabled, queryClient, sessionId]);

  return connectionState;
}

async function waitForReconnect(milliseconds: number, signal: AbortSignal) {
  if (signal.aborted) return false;
  return new Promise<boolean>((resolve) => {
    const timeout = window.setTimeout(() => {
      signal.removeEventListener('abort', cancel);
      resolve(true);
    }, milliseconds);
    const cancel = () => {
      window.clearTimeout(timeout);
      resolve(false);
    };
    signal.addEventListener('abort', cancel, { once: true });
  });
}

export function useUpdateResourcePolicy(sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (policy: ResourcePolicyRequest) =>
      updateSessionResourcePolicy(
        sessionId,
        policy,
        `resource-policy-${crypto.randomUUID()}`
      ),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: sessionKeys.resources(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.resourceEvents(sessionId),
        }),
      ]);
    },
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
        queryClient.invalidateQueries({
          queryKey: sessionKeys.resources(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.safePoint(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.migration(sessionId),
        }),
        queryClient.invalidateQueries({ queryKey: sessionKeys.all }),
      ]);
    },
  });
}
