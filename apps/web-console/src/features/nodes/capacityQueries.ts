import { useQuery } from '@tanstack/react-query';
import { listBrowserNodes, listExtensionProfiles } from '@/api/capacity';

export function useBrowserNodes() {
  return useQuery({
    queryKey: ['browser-nodes'],
    queryFn: ({ signal }) => listBrowserNodes(signal),
    refetchInterval: 5_000,
  });
}

export function useExtensionProfiles() {
  return useQuery({
    queryKey: ['extension-profiles'],
    queryFn: ({ signal }) => listExtensionProfiles(signal),
    refetchInterval: 15_000,
  });
}
