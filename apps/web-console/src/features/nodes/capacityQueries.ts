import { queryOptions, useQuery } from '@tanstack/react-query';
import { listBrowserNodes, listExtensionProfiles } from '@/api/capacity';

export const browserNodesKey = ['browser-nodes'] as const;
export const browserNodeQueryOptions = queryOptions({
  queryKey: browserNodesKey,
  queryFn: ({ signal }) => listBrowserNodes(signal),
});

export function useBrowserNodes() {
  return useQuery(browserNodeQueryOptions);
}

export function useExtensionProfiles(enabled = true) {
  return useQuery({
    queryKey: ['extension-profiles'],
    queryFn: ({ signal }) => listExtensionProfiles(signal),
    enabled,
    refetchInterval: enabled ? 15_000 : false,
  });
}
