import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  acceptAgentHandoff,
  approveAgentTask,
  createAgentTask,
  executeAgentTask,
  listAgentTasks,
  rejectAgentHandoff,
  rejectAgentTask,
} from '@/api/agent';
import type { CreateAgentTaskRequest } from '@/types/agent';

const agentTaskKeys = {
  all: ['agent-tasks'] as const,
  list: () => [...agentTaskKeys.all, 'list'] as const,
};

export function useAgentTasks() {
  return useQuery({
    queryKey: agentTaskKeys.list(),
    queryFn: ({ signal }) => listAgentTasks(undefined, signal),
    refetchInterval: (query) =>
      query.state.data?.items.some((task) =>
        ['RUNNING', 'AWAITING_CONFIRMATION', 'WAITING_FOR_HUMAN'].includes(
          task.state
        )
      )
        ? 2_000
        : 5_000,
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
