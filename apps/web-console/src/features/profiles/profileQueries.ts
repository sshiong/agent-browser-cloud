import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createProfile,
  createProfileExportGrant,
  importProfileCheckpoint,
  listProfileImports,
  listProfiles,
  redeemProfileExportGrant,
} from '@/api/profile';
import { currentActorId, currentTenantId } from '@/api/session';
import type {
  CreateProfileRequest,
  ProfileImportRequest,
  ProfileExportPurpose,
} from '@/types/profile';

export const profileKeys = {
  all: ['profiles'] as const,
  list: () => [...profileKeys.all, 'list'] as const,
  imports: (tenantId: string, actorId: string) =>
    [...profileKeys.all, 'imports', tenantId, actorId] as const,
};

export function useProfiles() {
  return useQuery({
    queryKey: profileKeys.list(),
    queryFn: ({ signal }) => listProfiles(undefined, signal),
  });
}

export function useProfileImports(enabled: boolean) {
  return useQuery({
    queryKey: profileKeys.imports(currentTenantId(), currentActorId()),
    queryFn: ({ signal }) => listProfileImports(undefined, signal),
    enabled,
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

export function useImportProfileCheckpoint() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: ProfileImportRequest) =>
      importProfileCheckpoint(request, `profile-import-${crypto.randomUUID()}`),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: profileKeys.all }),
  });
}

export function useProfileExport() {
  return useMutation({
    mutationFn: async ({
      profileId,
      purpose,
    }: {
      profileId: string;
      purpose: ProfileExportPurpose;
    }) => {
      const grant = await createProfileExportGrant(
        profileId,
        purpose,
        `profile-export-${crypto.randomUUID()}`
      );
      return redeemProfileExportGrant(profileId, grant.grantId);
    },
  });
}
