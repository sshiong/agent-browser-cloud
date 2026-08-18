import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  generateRecoveryGameDayReport,
  getEnterpriseOverview,
  getRecoveryGameDayEvents,
  streamEnterpriseOverviewChanges,
  updateRecoveryGameDayRemediation,
} from '@/api/enterprise';
import type { ResourceStreamConnectionState } from '@/types/session';

export const enterpriseOverviewKey = ['enterprise-overview'] as const;
export const enterpriseOverviewQueryOptions = {
  queryKey: enterpriseOverviewKey,
  queryFn: ({ signal }: { signal: AbortSignal }) =>
    getEnterpriseOverview(signal),
};

export function useEnterpriseOverview() {
  return useQuery(enterpriseOverviewQueryOptions);
}

export function useEnterpriseOverviewStream(
  enabled: boolean
): ResourceStreamConnectionState {
  const queryClient = useQueryClient();
  const [connectionState, setConnectionState] =
    useState<ResourceStreamConnectionState>(enabled ? 'CONNECTING' : 'IDLE');

  useEffect(() => {
    if (!enabled) {
      setConnectionState('IDLE');
      return;
    }
    const controller = new AbortController();
    let lastEventId: string | undefined;
    let reconnectAttempt = 0;

    const refreshOverview = () =>
      queryClient.invalidateQueries({ queryKey: enterpriseOverviewKey });

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
          await streamEnterpriseOverviewChanges({
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
              void refreshOverview();
            },
            onChange: (change) => {
              lastEventId = String(change.sequence);
              void refreshOverview();
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

export function useRecoveryGameDayEvents(gameDayId?: string) {
  return useQuery({
    queryKey: ['recovery-gameday-events', gameDayId],
    queryFn: ({ signal }) => getRecoveryGameDayEvents(gameDayId!, signal),
    enabled: Boolean(gameDayId),
    refetchInterval: 5_000,
  });
}

export function useGenerateRecoveryGameDayReport() {
  return useMutation({
    mutationFn: generateRecoveryGameDayReport,
  });
}

export function useUpdateRecoveryGameDayRemediation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      ticketId,
      input,
    }: {
      ticketId: string;
      input: {
        state: 'ACKNOWLEDGED' | 'RESOLVED';
        ownerId: string;
        resolution?: string;
      };
    }) => updateRecoveryGameDayRemediation(ticketId, input),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: enterpriseOverviewKey }),
  });
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
