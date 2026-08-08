import {
  useInfiniteQuery,
  useMutation,
  useQueryClient,
} from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import {
  listWorkspaceNotifications,
  streamWorkspaceNotificationChanges,
  updateWorkspaceNotificationReadCursor,
} from '@/api/notification';
import type { WorkspaceNotificationConnectionState } from '@/types/notification';

export const notificationKeys = {
  all: ['workspace-notifications'] as const,
  feed: () => [...notificationKeys.all, 'feed'] as const,
};

export function useWorkspaceNotifications(enabled: boolean) {
  return useInfiniteQuery({
    queryKey: notificationKeys.feed(),
    queryFn: ({ pageParam, signal }) =>
      listWorkspaceNotifications(30, pageParam, signal),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (lastPage) => lastPage.nextBeforeSequence ?? undefined,
    enabled,
    staleTime: 8_000,
  });
}

export function useWorkspaceNotificationStream(
  enabled: boolean
): WorkspaceNotificationConnectionState {
  const queryClient = useQueryClient();
  const [connectionState, setConnectionState] =
    useState<WorkspaceNotificationConnectionState>(
      enabled ? 'CONNECTING' : 'IDLE'
    );

  useEffect(() => {
    if (!enabled) {
      setConnectionState('IDLE');
      return;
    }
    const controller = new AbortController();
    let lastEventId: string | undefined;
    let reconnectAttempt = 0;

    const refreshFeed = () =>
      queryClient.invalidateQueries({ queryKey: notificationKeys.all });

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
          await streamWorkspaceNotificationChanges({
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
              void refreshFeed();
            },
            onChange: (change) => {
              lastEventId = String(change.sequence);
              void refreshFeed();
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

export function useMarkWorkspaceNotificationsRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: updateWorkspaceNotificationReadCursor,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: notificationKeys.all });
    },
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
