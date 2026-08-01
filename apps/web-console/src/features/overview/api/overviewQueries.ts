import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getWorkspaceOverview,
  streamWorkspaceOverviewChanges,
} from '@/api/workspaceOverview';
import { sessionKeys } from '@/features/sessions/api/sessionQueries';
import type { WorkspaceOverviewConnectionState } from '@/types/workspaceOverview';

export const workspaceOverviewKey = ['workspace-overview'] as const;

export function useWorkspaceOverview() {
  return useQuery({
    queryKey: workspaceOverviewKey,
    queryFn: ({ signal }) => getWorkspaceOverview(signal),
  });
}

export function useWorkspaceOverviewStream(
  enabled: boolean
): WorkspaceOverviewConnectionState {
  const queryClient = useQueryClient();
  const [connectionState, setConnectionState] =
    useState<WorkspaceOverviewConnectionState>(enabled ? 'CONNECTING' : 'IDLE');

  useEffect(() => {
    if (!enabled) {
      setConnectionState('IDLE');
      return;
    }
    const controller = new AbortController();
    let lastEventId: string | undefined;
    let reconnectAttempt = 0;

    const refreshOverview = () =>
      queryClient.invalidateQueries({ queryKey: workspaceOverviewKey });

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
          await streamWorkspaceOverviewChanges({
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
              if (
                change.changeType === 'SESSION' ||
                change.changeType === 'OPERATION'
              ) {
                void queryClient.invalidateQueries({
                  queryKey: sessionKeys.all,
                });
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
