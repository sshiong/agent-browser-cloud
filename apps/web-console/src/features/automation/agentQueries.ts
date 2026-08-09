import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import {
  acceptAgentHandoff,
  approveAgentTask,
  createAgentTask,
  executeAgentTask,
  getAgentTask,
  listAgentTaskSummaries,
  listAgentTasks,
  rejectAgentHandoff,
  rejectAgentTask,
} from '@/api/agent';
import type { CreateAgentTaskRequest } from '@/types/agent';

const agentTaskKeys = {
  all: ['agent-tasks'] as const,
  list: () => [...agentTaskKeys.all, 'list'] as const,
  summaries: () => [...agentTaskKeys.all, 'summaries'] as const,
  detail: (taskId: string) => [...agentTaskKeys.all, 'detail', taskId] as const,
};

export function useAgentTasks() {
  return useQuery({
    queryKey: agentTaskKeys.list(),
    queryFn: ({ signal }) => listAgentTasks(undefined, signal),
    refetchInterval: (query) =>
      query.state.data?.items.some((task) =>
        [
          'QUEUED',
          'AWAITING_REVIEW',
          'RUNNING',
          'AWAITING_CONFIRMATION',
          'WAITING_FOR_HUMAN',
          'PAUSED_BY_RESOURCE_POLICY',
        ].includes(task.state)
      )
        ? 2_000
        : 5_000,
  });
}

export function useAgentTaskSummaries() {
  return useInfiniteQuery({
    queryKey: agentTaskKeys.summaries(),
    queryFn: ({ pageParam, signal }) =>
      listAgentTaskSummaries(20, pageParam, undefined, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    refetchInterval: (query) => {
      const pages = query.state.data?.pages;
      const firstPage = pages?.[0];
      if (!firstPage || pages.length !== 1) return false;
      return firstPage.items.some((task) =>
        [
          'QUEUED',
          'AWAITING_REVIEW',
          'RUNNING',
          'AWAITING_CONFIRMATION',
          'WAITING_FOR_HUMAN',
          'PAUSED_BY_RESOURCE_POLICY',
        ].includes(task.state)
      )
        ? 2_000
        : false;
    },
  });
}

export function useAgentTask(taskId: string) {
  return useQuery({
    queryKey: agentTaskKeys.detail(taskId),
    queryFn: ({ signal }) => getAgentTask(taskId, undefined, signal),
    enabled: Boolean(taskId),
    refetchInterval: (query) =>
      query.state.data &&
      [
        'QUEUED',
        'AWAITING_REVIEW',
        'RUNNING',
        'AWAITING_CONFIRMATION',
        'WAITING_FOR_HUMAN',
        'PAUSED_BY_RESOURCE_POLICY',
      ].includes(query.state.data.state)
        ? 2_000
        : false,
  });
}

function useAgentDecision(decision: (taskId: string) => Promise<unknown>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: decision,
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: agentTaskKeys.all }),
  });
}

export function useApproveAgentTask() {
  return useAgentDecision(approveAgentTask);
}

export function useRejectAgentTask() {
  return useAgentDecision(rejectAgentTask);
}

export function useAcceptAgentHandoff() {
  return useAgentDecision(acceptAgentHandoff);
}

export function useRejectAgentHandoff() {
  return useAgentDecision(rejectAgentHandoff);
}

export function useCreateAgentTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      sessionId,
      request,
    }: {
      sessionId: string;
      request: CreateAgentTaskRequest;
    }) =>
      createAgentTask(sessionId, request, `agent-task-${crypto.randomUUID()}`),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: agentTaskKeys.all }),
  });
}

export function useExecuteAgentTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (taskId: string) =>
      executeAgentTask(taskId, `agent-execute-${crypto.randomUUID()}`),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: agentTaskKeys.all }),
  });
}
