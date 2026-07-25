import { DEFAULT_TENANT_ID, SessionApiError } from '@/api/session';
import type {
  AgentTaskListResponse,
  AgentTaskView,
  CreateAgentTaskRequest,
} from '@/types/agent';

const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
const API_BASE = (configuredBase || '/api/v1').replace(/\/$/, '');

async function request<T>(
  path: string,
  options?: RequestInit,
  tenantId = DEFAULT_TENANT_ID
): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'X-Tenant-Id': tenantId,
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
