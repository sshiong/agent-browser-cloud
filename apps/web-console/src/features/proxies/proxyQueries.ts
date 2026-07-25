import { useQuery } from '@tanstack/react-query';
import { getProxyOverview } from '@/api/proxy';

export function useProxyOverview() {
  return useQuery({
    queryKey: ['proxies', 'overview'],
    queryFn: ({ signal }) => getProxyOverview(undefined, signal),
    refetchInterval: 5_000,
  });
}
