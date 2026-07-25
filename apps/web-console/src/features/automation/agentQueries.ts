import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createAgentTask, executeAgentTask, listAgentTasks } from '@/api/agent';
import type { CreateAgentTaskRequest } from '@/types/agent';

const agentTaskKeys = {
  all: ['agent-tasks'] as const,
  list: () => [...agentTaskKeys.all, 'list'] as const,
};

export function useAgentTasks() {
  return useQuery({
    queryKey: agentTaskKeys.list(),
    queryFn: ({ signal }) => listAgentTasks(undefined, signal),
    refetchInterval: 5_000,
  });
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
