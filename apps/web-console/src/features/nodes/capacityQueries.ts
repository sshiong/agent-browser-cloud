import { useQuery } from '@tanstack/react-query';
import { listBrowserNodes, listExtensionProfiles } from '@/api/capacity';

export function useBrowserNodes() {
  return useQuery({
    queryKey: ['browser-nodes'],
    queryFn: ({ signal }) => listBrowserNodes(signal),
    refetchInterval: 5_000,
  });
}

export function useExtensionProfiles(enabled = true) {
  return useQuery({
    queryKey: ['extension-profiles'],
    queryFn: ({ signal }) => listExtensionProfiles(signal),
    enabled,
    refetchInterval: enabled ? 15_000 : false,
  });
}
