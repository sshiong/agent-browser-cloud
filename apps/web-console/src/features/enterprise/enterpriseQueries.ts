import { useQuery } from '@tanstack/react-query';
import { getEnterpriseOverview } from '@/api/enterprise';

export function useEnterpriseOverview() {
  return useQuery({
    queryKey: ['enterprise-overview'],
    queryFn: ({ signal }) => getEnterpriseOverview(signal),
    refetchInterval: 15_000,
  });
}
