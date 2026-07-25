import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createProfile, listProfiles } from '@/api/profile';
import type { CreateProfileRequest } from '@/types/profile';

export const profileKeys = {
  all: ['profiles'] as const,
  list: () => [...profileKeys.all, 'list'] as const,
};

export function useProfiles() {
  return useQuery({
    queryKey: profileKeys.list(),
    queryFn: ({ signal }) => listProfiles(undefined, signal),
  });
}

export function useCreateProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateProfileRequest) => createProfile(request),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: profileKeys.all }),
  });
}
