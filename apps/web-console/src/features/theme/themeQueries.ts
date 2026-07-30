import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getUserPreferences,
  updateUserPreferences,
} from '@/api/userPreferences';
import type { ThemeMode, UserPreferencesView } from '@/types/userPreferences';

export const userPreferenceKeys = {
  all: ['user-preferences'] as const,
  actor: (tenantId?: string, actorId?: string) =>
    [...userPreferenceKeys.all, tenantId, actorId] as const,
};

export function useUserPreferences(
  tenantId?: string,
  actorId?: string,
  enabled = true
) {
  return useQuery({
    queryKey: userPreferenceKeys.actor(tenantId, actorId),
    queryFn: ({ signal }) => getUserPreferences(signal),
    enabled: enabled && Boolean(tenantId && actorId),
    staleTime: 60_000,
  });
}

export function useUpdateUserPreferences(tenantId?: string, actorId?: string) {
  const queryClient = useQueryClient();
  const queryKey = userPreferenceKeys.actor(tenantId, actorId);
  return useMutation({
    mutationFn: (themeMode: ThemeMode) => updateUserPreferences({ themeMode }),
    onMutate: async (themeMode) => {
      await queryClient.cancelQueries({ queryKey });
      const previous = queryClient.getQueryData<UserPreferencesView>(queryKey);
      queryClient.setQueryData<UserPreferencesView>(queryKey, {
        themeMode,
        source: 'USER_OVERRIDE',
        updatedAt: previous?.updatedAt ?? null,
        version: previous?.version ?? 0,
      });
      return { previous };
    },
    onError: (_error, _themeMode, context) => {
      if (context?.previous) {
        queryClient.setQueryData(queryKey, context.previous);
      } else {
        queryClient.removeQueries({ queryKey });
      }
    },
    onSuccess: (preferences) => {
      queryClient.setQueryData(queryKey, preferences);
    },
  });
}
