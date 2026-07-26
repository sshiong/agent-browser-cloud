import { useQuery } from '@tanstack/react-query';
import { listAuditEvents, listRuntimeBuilds } from '@/api/platform';

export function useAuditEvents(eventType?: string) {
  return useQuery({
    queryKey: ['audit-events', eventType ?? 'all'],
    queryFn: ({ signal }) => listAuditEvents(eventType, signal),
    refetchInterval: 5000,
  });
}

export function useRuntimeBuilds() {
  return useQuery({
    queryKey: ['runtime-builds'],
    queryFn: ({ signal }) => listRuntimeBuilds(signal),
  });
}
