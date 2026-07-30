import {
  useInfiniteQuery,
  useMutation,
  useQueryClient,
} from '@tanstack/react-query';
import {
  listWorkspaceNotifications,
  updateWorkspaceNotificationReadCursor,
} from '@/api/notification';

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
    refetchInterval: enabled ? 15_000 : false,
  });
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
