import {
  currentActorId,
  DEFAULT_ACTOR_ID,
  DEFAULT_TENANT_ID,
  identityHeaders,
  SessionApiError,
} from '@/api/session';
import type {
  AgentTaskListResponse,
  AgentTaskSummaryListResponse,
  AgentTaskView,
  CreateAgentTaskRequest,
} from '@/types/agent';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

async function request<T>(
  path: string,
  options?: RequestInit,
  tenantId = DEFAULT_TENANT_ID,
  actorId = DEFAULT_ACTOR_ID
): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...identityHeaders(tenantId, actorId),
      ...options?.headers,
    },
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({
      code: 'UNKNOWN_ERROR',
      message: `Request failed with status ${response.status}`,
    }));
    throw new SessionApiError(response.status, body);
  }
  return response.json();
}

export function listAgentTasks(
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentTaskListResponse>(
    '/agent-tasks?limit=100&offset=0',
    { signal },
    tenantId
  );
}

export function listAgentTaskSummaries(
  limit = 20,
  cursor?: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  const parameters = new URLSearchParams({ limit: String(limit) });
  if (cursor) parameters.set('cursor', cursor);
  return request<AgentTaskSummaryListResponse>(
    `/agent-task-summaries?${parameters.toString()}`,
    { signal },
    tenantId
  );
}

export function getAgentTask(
  taskId: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentTaskView>(
    `/agent-tasks/${encodeURIComponent(taskId)}`,
    { signal },
    tenantId
  );
}

export function createAgentTask(
  sessionId: string,
  data: CreateAgentTaskRequest,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentTaskView>(
    `/sessions/${encodeURIComponent(sessionId)}/agent-tasks`,
    {
      method: 'POST',
      body: JSON.stringify(data),
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

export function executeAgentTask(
  taskId: string,
  idempotencyKey: string,
  tenantId = DEFAULT_TENANT_ID,
  signal?: AbortSignal
) {
  return request<AgentTaskView>(
    `/agent-tasks/${encodeURIComponent(taskId)}:execute`,
    {
      method: 'POST',
      signal,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
    tenantId
  );
}

function humanDecision(
  taskId: string,
  action: 'approve' | 'reject' | 'accept-handoff' | 'reject-handoff',
  tenantId = DEFAULT_TENANT_ID,
  actorId = currentActorId(),
  signal?: AbortSignal
) {
  return request<AgentTaskView>(
    `/agent-tasks/${encodeURIComponent(taskId)}:${action}`,
    {
      method: 'POST',
      signal,
    },
    tenantId,
    actorId
  );
}

export const approveAgentTask = (taskId: string) =>
  humanDecision(taskId, 'approve');
export const rejectAgentTask = (taskId: string) =>
  humanDecision(taskId, 'reject');
export const acceptAgentHandoff = (taskId: string) =>
  humanDecision(taskId, 'accept-handoff');
export const rejectAgentHandoff = (taskId: string) =>
  humanDecision(taskId, 'reject-handoff');
