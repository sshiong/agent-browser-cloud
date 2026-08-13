import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
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
  streamAuditEventChanges,
  transitionBreakGlassRequest,
  transitionKeyRotationRequest,
} from '@/api/platform';
import type {
  AuditStreamConnectionState,
  CompleteKeyRotationRequest,
  CreateBreakGlassRequest,
  CreateKeyRotationRequest,
} from '@/types/platform';

export const platformKeys = {
  auditEvents: ['audit-events'] as const,
  breakGlassRequests: ['break-glass-requests'] as const,
  keyRotationRequests: ['key-rotation-requests'] as const,
  secureDebugSessions: ['secure-debug-sessions'] as const,
};

/**
 * Governance ledgers whose every state transition is admitted into the workspace notification
 * projection by whole prefix (BREAK_GLASS_%, KEY_ROTATION_%, SECURE_DEBUG_%). The notification
 * cursor therefore covers them without loss and replaces their fixed polling.
 *
 * `audit-events` is deliberately excluded: it lists the full audit ledger, while the projection
 * only carries high-signal rows. Driving it from the notification cursor would trade "stale for
 * at most one interval" for "never refreshes when an event is not projected"; it follows the
 * audit chain cursor in `useAuditEventStream` instead.
 */
export const NOTIFICATION_DRIVEN_PLATFORM_KEYS = [
  platformKeys.breakGlassRequests,
  platformKeys.keyRotationRequests,
  platformKeys.secureDebugSessions,
] as const;

export function useAuditEvents(eventType?: string) {
  return useQuery({
    queryKey: [...platformKeys.auditEvents, eventType ?? 'all'],
    queryFn: ({ signal }) => listAuditEvents(eventType, signal),
  });
}

/**
 * Follows the tenant audit chain sequence. Every audited row advances this cursor, so unlike the
 * high-signal notification projection it can fully replace polling for the audit ledger.
 */
export function useAuditEventStream(
  enabled: boolean
): AuditStreamConnectionState {
  const queryClient = useQueryClient();
  const [connectionState, setConnectionState] =
    useState<AuditStreamConnectionState>(enabled ? 'CONNECTING' : 'IDLE');

  useEffect(() => {
    if (!enabled) {
      setConnectionState('IDLE');
      return;
    }
    const controller = new AbortController();
    let lastEventId: string | undefined;
    let reconnectAttempt = 0;

    const refreshAudit = () =>
      queryClient.invalidateQueries({ queryKey: platformKeys.auditEvents });

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
          await streamAuditEventChanges({
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
              void refreshAudit();
            },
            onChange: (change) => {
              lastEventId = String(change.sequence);
              void refreshAudit();
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
  }, [enabled, queryClient]);

  return connectionState;
}

async function waitForReconnect(milliseconds: number, signal: AbortSignal) {
  if (signal.aborted) return false;
  return new Promise<boolean>((resolve) => {
    const timeout = window.setTimeout(() => {
      signal.removeEventListener('abort', abort);
      resolve(true);
    }, milliseconds);
    const abort = () => {
      window.clearTimeout(timeout);
      resolve(false);
    };
    signal.addEventListener('abort', abort, { once: true });
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
    queryKey: platformKeys.breakGlassRequests,
    queryFn: ({ signal }) => listBreakGlassRequests(signal),
  });
}

export function useCreateBreakGlassRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateBreakGlassRequest) =>
      createBreakGlassRequest(input),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: platformKeys.breakGlassRequests,
        }),
        queryClient.invalidateQueries({ queryKey: platformKeys.auditEvents }),
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
        queryClient.invalidateQueries({
          queryKey: platformKeys.breakGlassRequests,
        }),
        queryClient.invalidateQueries({ queryKey: platformKeys.auditEvents }),
      ]);
    },
  });
}

export function useSecureDebugSessions() {
  return useQuery({
    queryKey: platformKeys.secureDebugSessions,
    queryFn: ({ signal }) => listSecureDebugSessions(signal),
  });
}

export function useStartSecureDebugSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (requestId: string) => startSecureDebugSession(requestId),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: platformKeys.secureDebugSessions,
        }),
        queryClient.invalidateQueries({
          queryKey: platformKeys.breakGlassRequests,
        }),
        queryClient.invalidateQueries({ queryKey: platformKeys.auditEvents }),
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
        queryClient.invalidateQueries({
          queryKey: platformKeys.secureDebugSessions,
        }),
        queryClient.invalidateQueries({ queryKey: platformKeys.auditEvents }),
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
        queryClient.invalidateQueries({
          queryKey: platformKeys.secureDebugSessions,
        }),
        queryClient.invalidateQueries({ queryKey: platformKeys.auditEvents }),
      ]);
    },
  });
}

export function useKeyRotationRequests() {
  return useQuery({
    queryKey: platformKeys.keyRotationRequests,
    queryFn: ({ signal }) => listKeyRotationRequests(signal),
  });
}

export function useCreateKeyRotationRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateKeyRotationRequest) =>
      createKeyRotationRequest(input),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: platformKeys.keyRotationRequests,
        }),
        queryClient.invalidateQueries({ queryKey: platformKeys.auditEvents }),
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
        queryClient.invalidateQueries({
          queryKey: platformKeys.keyRotationRequests,
        }),
        queryClient.invalidateQueries({ queryKey: platformKeys.auditEvents }),
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
        queryClient.invalidateQueries({
          queryKey: platformKeys.keyRotationRequests,
        }),
        queryClient.invalidateQueries({ queryKey: platformKeys.auditEvents }),
      ]);
    },
  });
}
