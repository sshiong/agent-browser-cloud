import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  generateRecoveryGameDayReport,
  getEnterpriseOverview,
  getRecoveryGameDayEvents,
  updateRecoveryGameDayRemediation,
} from '@/api/enterprise';

export function useEnterpriseOverview() {
  return useQuery({
    queryKey: ['enterprise-overview'],
    queryFn: ({ signal }) => getEnterpriseOverview(signal),
    refetchInterval: 15_000,
  });
}

export function useRecoveryGameDayEvents(gameDayId?: string) {
  return useQuery({
    queryKey: ['recovery-gameday-events', gameDayId],
    queryFn: ({ signal }) => getRecoveryGameDayEvents(gameDayId!, signal),
    enabled: Boolean(gameDayId),
    refetchInterval: 5_000,
  });
}

export function useGenerateRecoveryGameDayReport() {
  return useMutation({
    mutationFn: generateRecoveryGameDayReport,
  });
}

export function useUpdateRecoveryGameDayRemediation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      ticketId,
      input,
    }: {
      ticketId: string;
      input: {
        state: 'ACKNOWLEDGED' | 'RESOLVED';
        ownerId: string;
        resolution?: string;
      };
    }) => updateRecoveryGameDayRemediation(ticketId, input),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ['enterprise-overview'] }),
  });
}
