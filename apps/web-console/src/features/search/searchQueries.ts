import { useQuery } from '@tanstack/react-query';
import { globalSearch } from '@/api/search';
import type { SearchResourceType } from '@/types/search';

export const searchKeys = {
  all: ['global-search'] as const,
  result: (query: string, types: SearchResourceType[]) =>
    [...searchKeys.all, query, [...types].sort()] as const,
};

export function useGlobalSearch(
  query: string,
  types: SearchResourceType[],
  enabled: boolean
) {
  const normalized = query.trim();
  return useQuery({
    queryKey: searchKeys.result(normalized, types),
    queryFn: ({ signal }) => globalSearch(normalized, types, 24, signal),
    enabled: enabled && normalized.length >= 2,
    staleTime: 5_000,
  });
}
